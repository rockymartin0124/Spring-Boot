/*
 * Copyright 2012-2022 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.boot.loader;

import java.io.File;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.output.ToStringConsumer;
import org.testcontainers.containers.startupcheck.OneShotStartupCheckStrategy;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import org.springframework.boot.system.JavaVersion;
import org.springframework.boot.testsupport.testcontainers.DisabledIfDockerUnavailable;
import org.springframework.util.Assert;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests loader that supports fat jars.
 *
 * @author Phillip Webb
 */
@DisabledIfDockerUnavailable
class LoaderIntegrationTests {

	private final ToStringConsumer output = new ToStringConsumer();

	@ParameterizedTest
	@MethodSource("javaRuntimes")
	void readUrlsWithoutWarning(JavaRuntime javaRuntime) {
		try (GenericContainer<?> container = createContainer(javaRuntime)) {
			container.start();
			System.out.println(this.output.toUtf8String());
			assertThat(this.output.toUtf8String()).contains(">>>>> 287649 BYTES from").doesNotContain("WARNING:")
					.doesNotContain("illegal").doesNotContain("jar written to temp");
		}
	}

	private GenericContainer<?> createContainer(JavaRuntime javaRuntime) {
		return javaRuntime.getContainer().withLogConsumer(this.output)
				.withCopyFileToContainer(MountableFile.forHostPath(findApplication().toPath()), "/app.jar")
				.withStartupCheckStrategy(new OneShotStartupCheckStrategy().withTimeout(Duration.ofMinutes(5)))
				.withCommand("java", "-jar", "app.jar");
	}

	private File findApplication() {
		String name = String.format("build/%1$s/build/libs/%1$s.jar", "spring-boot-loader-tests-app");
		File jar = new File(name);
		Assert.state(jar.isFile(), () -> "Could not find " + name + ". Have you built it?");
		return jar;
	}

	static Stream<JavaRuntime> javaRuntimes() {
		List<JavaRuntime> javaRuntimes = new ArrayList<>();
		javaRuntimes.add(JavaRuntime.openJdk(JavaVersion.SEVENTEEN));
		javaRuntimes.add(JavaRuntime.openJdk(JavaVersion.NINETEEN));
		javaRuntimes.add(JavaRuntime.oracleJdk17());
		return javaRuntimes.stream().filter(JavaRuntime::isCompatible);
	}

	static final class JavaRuntime {

		private final String name;

		private final JavaVersion version;

		private final Supplier<GenericContainer<?>> container;

		private JavaRuntime(String name, JavaVersion version, Supplier<GenericContainer<?>> container) {
			this.name = name;
			this.version = version;
			this.container = container;
		}

		private boolean isCompatible() {
			return this.version.isEqualOrNewerThan(JavaVersion.getJavaVersion());
		}

		GenericContainer<?> getContainer() {
			return this.container.get();
		}

		@Override
		public String toString() {
			return this.name;
		}

		static JavaRuntime openJdk(JavaVersion version) {
			String imageVersion = version.toString();
			DockerImageName image = DockerImageName.parse("bellsoft/liberica-openjdk-debian:" + imageVersion);
			return new JavaRuntime("OpenJDK " + imageVersion, version, () -> new GenericContainer<>(image));
		}

		static JavaRuntime oracleJdk17() {
			ImageFromDockerfile image = new ImageFromDockerfile("spring-boot-loader/oracle-jdk-17")
					.withFileFromFile("Dockerfile", new File("src/intTest/resources/conf/oracle-jdk-17/Dockerfile"));
			return new JavaRuntime("Oracle JDK 17", JavaVersion.SEVENTEEN, () -> new GenericContainer<>(image));
		}

	}

}
