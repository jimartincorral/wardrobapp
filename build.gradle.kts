// Test guards applied to every Kotlin module, rather than repeated in each.
//
// Both exist because a test task that verifies nothing reports success exactly
// like one that verified everything, and the whole reason these modules are
// plain Kotlin is that their behaviour can be checked without a device.
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
                        "No Kotlin test sources found in ${project.path}. These modules are " +
                            "pure so that they can be tested, so an untested one is a failure, " +
                            "not a pass."
                    )
                }
            }
        }

        tasks.withType<Test>().configureEach {
            dependsOn(verifyTestSourcesExist)
            useJUnitPlatform()
            testLogging {
                events("failed")
                // The message, not just the line number. :app's tests run nowhere
                // but CI, so a failure that says only "AssertionError at line 60"
                // costs a whole round trip to find out what it was asserting.
                exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
            }

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

    // The same two guards for the modules that have become Kotlin Multiplatform,
    // plus a third thing only they need: a task called `test`.
    //
    // The multiplatform plugin names a target's tests after the target -- `jvmTest`
    // -- and creates no `test` at all. `./gradlew test` is what CI and every
    // contributor runs, and asked for a task a project does not have, Gradle runs
    // it in the projects that do and says nothing about the rest: the module's
    // tests would stop running and the build would stay green, which is the exact
    // failure the guards above were written for, arriving by a route they cannot
    // see. So `test` is defined here, for every such module, rather than trusted
    // to each module's build file.
    //
    // It also compiles every non-JVM target. Common code that only compiles on the
    // JVM -- a `java.*` import, `String.format` -- is invisible to the JVM tests
    // and breaks the browser build, so `test` is where it should fail: on any
    // machine, in seconds, before CI. The Wasm compiler needs nothing beyond Maven
    // Central. Running the tests there too would need Node.js, which is why it is
    // compiled and not yet run; the tests are the same common code either way.
    //
    // Repeated rather than shared with the block above because what differs is
    // the part that matters: where the test sources live (one directory per
    // source set here, against the Java plugin's single `test` set there), and
    // the task that has to exist.
    plugins.withId("org.jetbrains.kotlin.multiplatform") {
        val verifyMultiplatformTestSourcesExist = tasks.register("verifyTestSourcesExist") {
            val sourceRoot = project.file("src")
            doLast {
                val testSets = sourceRoot.listFiles { dir -> dir.isDirectory && dir.name.endsWith("Test") }.orEmpty()
                if (testSets.none { set -> set.walkTopDown().any { it.extension == "kt" } }) {
                    throw GradleException(
                        "No Kotlin test sources found in ${project.path}. These modules are " +
                            "pure so that they can be tested, so an untested one is a failure, " +
                            "not a pass."
                    )
                }
            }
        }

        tasks.withType<Test>().configureEach {
            dependsOn(verifyMultiplatformTestSourcesExist)
            useJUnitPlatform()
            testLogging {
                events("failed")
                exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
            }

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

        // Registered after the module's own build file has run, and only if
        // nothing else has: :ui is also an Android library, and the Android plugin
        // brings a `test` of its own -- its unit tests, of which :ui has none. Two
        // tasks cannot share the name, so there the existing one is given the JVM
        // tests to run as well.
        project.afterEvaluate {
            val runs = tasks.matching { it.name == "jvmTest" || it.name == "compileKotlinWasmJs" }
            if (tasks.findByName("test") == null) {
                tasks.register("test") {
                    group = "verification"
                    description = "Runs the JVM tests and compiles every other target. See the root build file."
                    dependsOn(runs)
                }
            } else {
                tasks.named("test") { dependsOn(runs) }
            }
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
            testLogging {
                events("failed")
                // The message, not just the line number. :app's tests run nowhere
                // but CI, so a failure that says only "AssertionError at line 60"
                // costs a whole round trip to find out what it was asserting.
                exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
            }

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
