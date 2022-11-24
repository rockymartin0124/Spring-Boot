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

package smoketest.rsocket;

import io.rsocket.metadata.WellKnownMimeType;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.rsocket.server.LocalRSocketServerPort;
import org.springframework.messaging.rsocket.RSocketRequester;
import org.springframework.security.rsocket.metadata.SimpleAuthenticationEncoder;
import org.springframework.security.rsocket.metadata.UsernamePasswordMetadata;
import org.springframework.util.MimeTypeUtils;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "spring.rsocket.server.port=0")
class SampleRSocketApplicationTests {

	@LocalRSocketServerPort
	private int port;

	@Autowired
	private RSocketRequester.Builder builder;

	@Test
	void unauthenticatedAccessToRSocketEndpoint() {
		RSocketRequester requester = this.builder.tcp("localhost", this.port);
		Mono<Project> result = requester.route("find.project.spring-boot").retrieveMono(Project.class);
		StepVerifier.create(result).expectErrorMessage("Access Denied").verify();
	}

	@Test
	void rSocketEndpoint() {
		RSocketRequester requester = this.builder
				.rsocketStrategies((builder) -> builder.encoder(new SimpleAuthenticationEncoder()))
				.setupMetadata(new UsernamePasswordMetadata("user", "password"),
						MimeTypeUtils.parseMimeType(WellKnownMimeType.MESSAGE_RSOCKET_AUTHENTICATION.getString()))
				.tcp("localhost", this.port);
		Mono<Project> result = requester.route("find.project.spring-boot").retrieveMono(Project.class);
		StepVerifier.create(result).assertNext((project) -> assertThat(project.getName()).isEqualTo("spring-boot"))
				.verifyComplete();
	}

}
