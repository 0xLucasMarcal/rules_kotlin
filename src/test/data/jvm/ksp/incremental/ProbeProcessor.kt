/*
 * Copyright 2026 The Bazel Authors. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package probe

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration

/**
 * For each class annotated with `probe.Probe`, generates `<Class>Probe` listing the functions of the
 * class, inherited ones included, and the resource `probe/<Class>.run` holding the value of the
 * `ksp.probe.run` system property, which tells the run that last processed the class. The outputs
 * of a class depend on its file only, as isolating outputs do.
 */
class ProbeProcessor(
  private val codeGenerator: CodeGenerator,
) : SymbolProcessor {
  override fun process(resolver: Resolver): List<KSAnnotated> {
    check(System.getProperty("ksp.probe.fail") == null) { "ksp.probe.fail is set" }
    val run = System.getProperty("ksp.probe.run", "0")
    resolver
      .getSymbolsWithAnnotation("probe.Probe")
      .filterIsInstance<KSClassDeclaration>()
      .forEach { declaration ->
        val name = declaration.simpleName.asString()
        val dependencies = Dependencies(false, declaration.containingFile!!)
        val functions =
          declaration
            .getAllFunctions()
            .map { it.simpleName.asString() }
            .filterNot { it in OBJECT_FUNCTIONS }
            .sorted()
            .joinToString { "\"$it\"" }
        codeGenerator.createNewFile(dependencies, "probe", "${name}Probe").use {
          it.write("package probe\n\nobject ${name}Probe {\n  val functions = listOf($functions)\n}\n".toByteArray())
        }
        codeGenerator.createNewFile(dependencies, "probe", name, "run").use {
          it.write(run.toByteArray())
        }
      }
    return emptyList()
  }

  private companion object {
    val OBJECT_FUNCTIONS = setOf("equals", "hashCode", "toString", "<init>")
  }
}

class ProbeProcessorProvider : SymbolProcessorProvider {
  override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor =
    ProbeProcessor(environment.codeGenerator)
}
