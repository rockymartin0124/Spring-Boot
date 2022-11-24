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

package org.springframework.boot.actuate.autoconfigure.observation.web.reactive;

import java.util.List;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.autoconfigure.metrics.CompositeMeterRegistryAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.metrics.MetricsAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.metrics.MetricsProperties;
import org.springframework.boot.actuate.autoconfigure.metrics.OnlyOnceLoggingDenyMeterFilter;
import org.springframework.boot.actuate.autoconfigure.metrics.export.simple.SimpleMetricsExportAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.observation.ObservationAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.observation.ObservationProperties;
import org.springframework.boot.actuate.metrics.web.reactive.server.WebFluxTagsContributor;
import org.springframework.boot.actuate.metrics.web.reactive.server.WebFluxTagsProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.server.reactive.observation.DefaultServerRequestObservationConvention;
import org.springframework.http.server.reactive.observation.ServerRequestObservationConvention;
import org.springframework.web.filter.reactive.ServerHttpObservationFilter;

/**
 * {@link EnableAutoConfiguration Auto-configuration} for instrumentation of Spring
 * WebFlux applications.
 *
 * @author Brian Clozel
 * @author Jon Schneider
 * @author Dmytro Nosan
 * @since 3.0.0
 */
@AutoConfiguration(after = { MetricsAutoConfiguration.class, CompositeMeterRegistryAutoConfiguration.class,
		SimpleMetricsExportAutoConfiguration.class, ObservationAutoConfiguration.class })
@ConditionalOnClass(Observation.class)
@ConditionalOnBean(ObservationRegistry.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
@EnableConfigurationProperties({ MetricsProperties.class, ObservationProperties.class })
@SuppressWarnings("removal")
public class WebFluxObservationAutoConfiguration {

	private final MetricsProperties metricsProperties;

	private final ObservationProperties observationProperties;

	public WebFluxObservationAutoConfiguration(MetricsProperties metricsProperties,
			ObservationProperties observationProperties) {
		this.metricsProperties = metricsProperties;
		this.observationProperties = observationProperties;
	}

	@Bean
	@ConditionalOnMissingBean
	public ServerHttpObservationFilter webfluxObservationFilter(ObservationRegistry registry,
			ObjectProvider<ServerRequestObservationConvention> customConvention,
			ObjectProvider<WebFluxTagsProvider> tagConfigurer,
			ObjectProvider<WebFluxTagsContributor> contributorsProvider) {
		String observationName = this.observationProperties.getHttp().getServer().getRequests().getName();
		String metricName = this.metricsProperties.getWeb().getServer().getRequest().getMetricName();
		String name = (observationName != null) ? observationName : metricName;
		WebFluxTagsProvider tagsProvider = tagConfigurer.getIfAvailable();
		List<WebFluxTagsContributor> tagsContributors = contributorsProvider.orderedStream().toList();
		ServerRequestObservationConvention convention = createConvention(customConvention.getIfAvailable(), name,
				tagsProvider, tagsContributors);
		return new ServerHttpObservationFilter(registry, convention);
	}

	private static ServerRequestObservationConvention createConvention(
			ServerRequestObservationConvention customConvention, String name, WebFluxTagsProvider tagsProvider,
			List<WebFluxTagsContributor> tagsContributors) {
		if (customConvention != null) {
			return customConvention;
		}
		if (tagsProvider != null) {
			return new ServerRequestObservationConventionAdapter(name, tagsProvider);
		}
		if (!tagsContributors.isEmpty()) {
			return new ServerRequestObservationConventionAdapter(name, tagsContributors);
		}
		return new DefaultServerRequestObservationConvention(name);
	}

	@Configuration(proxyBeanMethods = false)
	@ConditionalOnClass(MeterRegistry.class)
	@ConditionalOnBean(MeterRegistry.class)
	static class MeterFilterConfiguration {

		@Bean
		@Order(0)
		MeterFilter metricsHttpServerUriTagFilter(MetricsProperties metricsProperties,
				ObservationProperties observationProperties) {
			String observationName = observationProperties.getHttp().getServer().getRequests().getName();
			String name = (observationName != null) ? observationName
					: metricsProperties.getWeb().getServer().getRequest().getMetricName();
			MeterFilter filter = new OnlyOnceLoggingDenyMeterFilter(
					() -> "Reached the maximum number of URI tags for '%s'.".formatted(name));
			return MeterFilter.maximumAllowableTags(name, "uri", metricsProperties.getWeb().getServer().getMaxUriTags(),
					filter);
		}

	}

}
