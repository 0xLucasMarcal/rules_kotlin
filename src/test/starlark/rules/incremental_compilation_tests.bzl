"""Tests of the incremental compilation opt-out tag."""

load("@rules_testing//lib:analysis_test.bzl", "analysis_test")
load("//kotlin:jvm.bzl", "kt_jvm_library")
load("//src/test/starlark:case.bzl", "suite")
load(":util.bzl", "values_for_flag_of")

def _opt_out_assertion(expected):
    def _assertion(env, target):
        action = env.expect.that_target(target).action_named("KotlinCompile")
        values_for_flag_of(action, "--incremental_compilation_opt_out").contains_exactly(expected)

    return _assertion

def _make_opt_out_test(tags, expected):
    def _case(test):
        library = test.got(
            kt_jvm_library,
            name = "library",
            srcs = [test.artifact(name = "library.kt")],
            tags = tags,
        )
        analysis_test(
            name = test.name,
            impl = _opt_out_assertion(expected),
            target = library,
        )

    return _case

def incremental_compilation_tests(name):
    suite(
        name,
        # Only tagged targets carry the flag: the other actions' command lines are unchanged.
        tagged_target_opts_out = _make_opt_out_test(["kt_no_incremental_compilation"], ["true"]),
        untagged_target_carries_no_flag = _make_opt_out_test([], []),
    )
