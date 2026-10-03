// The plugins every module uses, and their versions, declared once here.
//
// Each module used to name its own, with the same version written beside it.
// That loads the Kotlin Gradle plugin once per module, in a class loader of each
// module's own, which Gradle tolerated with a warning until the browser app
// arrived: setting up Node.js for a Wasm executable is done once for the whole
// build, and KGP refuses outright to do it when it finds copies of itself in
// different class loaders. Declared here, it is loaded once, in the root's
// class loader, and every module applies it from there by id.
//
// The Android Gradle plugin has to be in the same class loader -- KGP's Android
// targets look for AGP's classes from wherever KGP itself was loaded -- but it
// is published only on Google's Maven, which settings.gradle.kts leaves out
// where there is no SDK so that the pure modules build anywhere. A `plugins`
// block cannot be conditional, so AGP goes on the root's classpath through
// `buildscript` instead, and only where there is an SDK: the same probe again,
// for the reason settings.gradle.kts gives for repeating it.
buildscript {
    val androidSdk = System.getenv("ANDROID_HOME")?.takeIf { it.isNotBlank() }
        ?: System.getenv("ANDROID_SDK_ROOT")?.takeIf { it.isNotBlank() }
        ?: java.io.File(rootDir, "local.properties")
            .takeIf { it.exists() }
            ?.readLines()
            ?.firstOrNull { it.startsWith("sdk.dir=") }
            ?.removePrefix("sdk.dir=")

    if (androidSdk != null && java.io.File(androidSdk).isDirectory) {
        repositories {
            google()
            mavenCentral()
        }
        dependencies {
            classpath("com.android.tools.build:gradle:8.9.1")
        }
    }
}

plugins {
    kotlin("multiplatform") version "2.1.20" apply false
    kotlin("jvm") version "2.1.20" apply false
    kotlin("android") version "2.1.20" apply false
    kotlin("plugin.serialization") version "2.1.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.20" apply false
    // Compose Multiplatform. 1.7, one line behind the newest, deliberately: see
    // ui/build.gradle.kts.
    id("org.jetbrains.compose") version "1.7.3" apply false
}

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

        // Registered only if nothing else has: :ui is also an Android library, and
        // the Android plugin brings a `test` of its own -- its unit tests, of which
        // :ui has none. Two tasks cannot share the name, so there the existing one
        // is given the JVM tests to run as well.
        //
        // Once every project is evaluated, not in this one's afterEvaluate, which
        // is what this first did and what broke CI. The Android plugin creates its
        // `test` in an afterEvaluate hook of its own, and those hooks run in the
        // order they were added: :ui applies the multiplatform plugin first, so
        // this check ran before the Android plugin's, found no `test`, registered
        // one, and the Android plugin then failed adding its own with "Cannot add
        // task 'test' as a task with that name already exists". projectsEvaluated
        // runs after every afterEvaluate in the build, so what it sees no longer
        // depends on the order a module lists its plugins in. Tasks can still be
        // registered then: nothing has built the task graph yet.
        gradle.projectsEvaluated {
            val tasks = project.tasks
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
