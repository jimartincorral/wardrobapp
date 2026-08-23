// Test guards applied to every Kotlin module, rather than repeated in each.
//
// Both exist because a test task that verifies nothing reports success exactly
// like one that verified everything, and these modules are ports whose entire
// value is the suite proving they match the TypeScript.
subprojects {
    val project = this
    plugins.withId("org.jetbrains.kotlin.jvm") {
        // With no test sources at all Gradle marks the test task NO-SOURCE and
        // skips it, so no test listener ever runs and the build goes green having
        // checked nothing. This task declares no inputs, so it always runs.
        val verifyTestSourcesExist = tasks.register("verifyTestSourcesExist") {
            // Resolved lazily: plugins.withId fires before the java extension
            // is registered, so looking it up eagerly here fails.
            val testSources = project.provider {
                project.extensions
                    .getByType<org.gradle.api.plugins.JavaPluginExtension>()
                    .sourceSets
                    .getByName("test")
                    .allSource
            }
            doLast {
                if (testSources.get().files.none { it.extension == "kt" }) {
                    throw GradleException(
                        "No Kotlin test sources found in ${project.path}. The parity suite is " +
                            "the point of these modules, so its absence is a failure, not a pass."
                    )
                }
            }
        }

        tasks.withType<Test>().configureEach {
            dependsOn(verifyTestSourcesExist)
            useJUnitPlatform()
            testLogging { events("failed") }

            afterSuite(
                KotlinClosure2<TestDescriptor, TestResult, Unit>({ descriptor, result ->
                    if (descriptor.parent == null && result.testCount == 0L) {
                        throw GradleException(
                            "No tests were discovered in ${project.path}. An empty run is a " +
                                "failure, not a pass."
                        )
                    }
                })
            )
        }
    }

    // The same two guards for the Android module, deliberately repeated rather
    // than shared: its tests run on JUnit 4, because Robolectric needs a runner,
    // so it must not get the useJUnitPlatform() above -- and its source sets come
    // from the Android extension rather than the Java one.
    //
    // Worth having for the same reason as the others, and learned the hard way:
    // this module's first test run went green while resolving no Android runtime
    // at all, and a green run that discovered nothing looks identical to one that
    // passed.
    plugins.withId("com.android.application") {
        val verifyAndroidTestSourcesExist = tasks.register("verifyAndroidTestSourcesExist") {
            val testSources = project.file("src/test/kotlin")
            doLast {
                if (testSources.walkTopDown().none { it.extension == "kt" }) {
                    throw GradleException(
                        "No Kotlin test sources found in ${project.path}. What lives there is " +
                            "the platform plumbing no other module can test, so its absence is " +
                            "a failure, not a pass."
                    )
                }
            }
        }

        tasks.withType<Test>().configureEach {
            dependsOn(verifyAndroidTestSourcesExist)
            testLogging { events("failed") }

            afterSuite(
                KotlinClosure2<TestDescriptor, TestResult, Unit>({ descriptor, result ->
                    if (descriptor.parent == null && result.testCount == 0L) {
                        throw GradleException(
                            "No tests were discovered in ${project.path}. An empty run is a " +
                                "failure, not a pass."
                        )
                    }
                })
            )
        }
    }
}
