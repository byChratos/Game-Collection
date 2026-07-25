plugins {
	kotlin("jvm") version "2.1.20"
	kotlin("plugin.spring") version "2.1.20"
	id("org.springframework.boot") version "3.5.0"
	id("io.spring.dependency-management") version "1.1.7"
}

group = "de.cfe.gamecollection"
version = "0.0.1-SNAPSHOT"
description = "Game Collection Backend"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(21)
	}
}

repositories {
	mavenCentral()
}

val webrtcVersion = "0.14.0"

// webrtc-java ships the JNI natives as classifier artifacts and picks one via Maven OS
// profiles (<activation><os>). Gradle does not evaluate Maven profiles, so the published
// POM's own "${platform.classifier}" self-dependency never resolves — we select the
// classifier here instead. Override with -Pwebrtc.platform=<classifier> when cross-building
// (e.g. producing a Linux image from a Windows host).
val webrtcPlatform: String = (findProperty("webrtc.platform") as String?) ?: run {
	val os = System.getProperty("os.name").lowercase()
	val arch = when (val a = System.getProperty("os.arch").lowercase()) {
		"amd64", "x86_64" -> "x86_64"
		"aarch64", "arm64" -> "aarch64"
		"arm", "aarch32" -> "aarch32"
		else -> error("webrtc-java has no native build for os.arch=$a")
	}
	when {
		os.startsWith("windows") -> "windows-x86_64".also {
			// Upstream publishes no windows-aarch64 artifact.
			check(arch == "x86_64") { "webrtc-java has no Windows native for $arch" }
		}
		os.startsWith("mac") -> "macos-$arch"
		else -> "linux-$arch"
	}
}

dependencies {
	implementation("org.springframework.boot:spring-boot-starter-actuator")
	implementation("org.springframework.boot:spring-boot-starter-web")
	implementation("org.springframework.boot:spring-boot-starter-websocket")
	implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
	implementation("org.jetbrains.kotlin:kotlin-reflect")

	// Java API classes. The transitive self-dependency on the unresolvable
	// "${platform.classifier}" artifact is excluded; the native jar is added explicitly below.
	implementation("dev.onvoid.webrtc:webrtc-java:$webrtcVersion") {
		exclude(group = "dev.onvoid.webrtc", module = "webrtc-java")
	}
	runtimeOnly("dev.onvoid.webrtc:webrtc-java:$webrtcVersion:$webrtcPlatform")

	testImplementation("org.springframework.boot:spring-boot-starter-test")
	testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
	compilerOptions {
		freeCompilerArgs.addAll("-Xjsr305=strict")
	}
}

tasks.withType<Test> {
	useJUnitPlatform()
}

// Only the bootJar fat jar is consumed (by scripts/build-backend.mjs -> jpackage).
// The plain jar has no Main-Class and would just sit in build/libs as a decoy.
tasks.named<Jar>("jar") {
	enabled = false
}
