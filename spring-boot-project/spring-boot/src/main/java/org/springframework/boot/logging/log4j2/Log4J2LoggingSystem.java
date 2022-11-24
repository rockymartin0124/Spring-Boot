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

package org.springframework.boot.logging.log4j2;

import java.io.IOException;
import java.net.URL;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.logging.ConsoleHandler;
import java.util.logging.Handler;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.AbstractConfiguration;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.ConfigurationFactory;
import org.apache.logging.log4j.core.config.ConfigurationSource;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.composite.CompositeConfiguration;
import org.apache.logging.log4j.core.filter.AbstractFilter;
import org.apache.logging.log4j.core.net.UrlConnectionFactory;
import org.apache.logging.log4j.core.net.ssl.SslConfiguration;
import org.apache.logging.log4j.core.net.ssl.SslConfigurationFactory;
import org.apache.logging.log4j.core.util.AuthorizationProvider;
import org.apache.logging.log4j.core.util.NameUtil;
import org.apache.logging.log4j.jul.Log4jBridgeHandler;
import org.apache.logging.log4j.message.Message;
import org.apache.logging.log4j.util.PropertiesUtil;

import org.springframework.boot.context.properties.bind.BindResult;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.logging.AbstractLoggingSystem;
import org.springframework.boot.logging.LogFile;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggerConfiguration;
import org.springframework.boot.logging.LoggingInitializationContext;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.boot.logging.LoggingSystemFactory;
import org.springframework.core.Conventions;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.CollectionUtils;
import org.springframework.util.ResourceUtils;
import org.springframework.util.StringUtils;

/**
 * {@link LoggingSystem} for <a href="https://logging.apache.org/log4j/2.x/">Log4j 2</a>.
 *
 * @author Daniel Fullarton
 * @author Andy Wilkinson
 * @author Alexander Heusingfeld
 * @author Ben Hale
 * @author Ralph Goers
 * @since 1.2.0
 */
public class Log4J2LoggingSystem extends AbstractLoggingSystem {

	private static final String FILE_PROTOCOL = "file";

	private static final String LOG4J_BRIDGE_HANDLER = "org.apache.logging.log4j.jul.Log4jBridgeHandler";

	private static final String LOG4J_LOG_MANAGER = "org.apache.logging.log4j.jul.LogManager";

	static final String ENVIRONMENT_KEY = Conventions.getQualifiedAttributeName(Log4J2LoggingSystem.class,
			"environment");

	private static final LogLevels<Level> LEVELS = new LogLevels<>();

	static {
		LEVELS.map(LogLevel.TRACE, Level.TRACE);
		LEVELS.map(LogLevel.DEBUG, Level.DEBUG);
		LEVELS.map(LogLevel.INFO, Level.INFO);
		LEVELS.map(LogLevel.WARN, Level.WARN);
		LEVELS.map(LogLevel.ERROR, Level.ERROR);
		LEVELS.map(LogLevel.FATAL, Level.FATAL);
		LEVELS.map(LogLevel.OFF, Level.OFF);
	}

	private static final Filter FILTER = new AbstractFilter() {

		@Override
		public Result filter(LogEvent event) {
			return Result.DENY;
		}

		@Override
		public Result filter(Logger logger, Level level, Marker marker, Message msg, Throwable t) {
			return Result.DENY;
		}

		@Override
		public Result filter(Logger logger, Level level, Marker marker, Object msg, Throwable t) {
			return Result.DENY;
		}

		@Override
		public Result filter(Logger logger, Level level, Marker marker, String msg, Object... params) {
			return Result.DENY;
		}

	};

	public Log4J2LoggingSystem(ClassLoader classLoader) {
		super(classLoader);
	}

	@Override
	protected String[] getStandardConfigLocations() {
		List<String> locations = new ArrayList<>();
		locations.add("log4j2-test.properties");
		if (isClassAvailable("com.fasterxml.jackson.dataformat.yaml.YAMLParser")) {
			Collections.addAll(locations, "log4j2-test.yaml", "log4j2-test.yml");
		}
		if (isClassAvailable("com.fasterxml.jackson.databind.ObjectMapper")) {
			Collections.addAll(locations, "log4j2-test.json", "log4j2-test.jsn");
		}
		locations.add("log4j2-test.xml");
		locations.add("log4j2.properties");
		if (isClassAvailable("com.fasterxml.jackson.dataformat.yaml.YAMLParser")) {
			Collections.addAll(locations, "log4j2.yaml", "log4j2.yml");
		}
		if (isClassAvailable("com.fasterxml.jackson.databind.ObjectMapper")) {
			Collections.addAll(locations, "log4j2.json", "log4j2.jsn");
		}
		locations.add("log4j2.xml");
		String propertyDefinedLocation = new PropertiesUtil(new Properties())
				.getStringProperty(ConfigurationFactory.CONFIGURATION_FILE_PROPERTY);
		if (propertyDefinedLocation != null) {
			locations.add(propertyDefinedLocation);
		}
		return StringUtils.toStringArray(locations);
	}

	protected boolean isClassAvailable(String className) {
		return ClassUtils.isPresent(className, getClassLoader());
	}

	@Override
	public void beforeInitialize() {
		LoggerContext loggerContext = getLoggerContext();
		if (isAlreadyInitialized(loggerContext)) {
			return;
		}
		if (!configureJdkLoggingBridgeHandler()) {
			super.beforeInitialize();
		}
		loggerContext.getConfiguration().addFilter(FILTER);
	}

	private boolean configureJdkLoggingBridgeHandler() {
		try {
			if (isJulUsingASingleConsoleHandlerAtMost() && !isLog4jLogManagerInstalled()
					&& isLog4jBridgeHandlerAvailable()) {
				removeDefaultRootHandler();
				Log4jBridgeHandler.install(false, null, true);
				return true;
			}
		}
		catch (Throwable ex) {
			// Ignore. No java.util.logging bridge is installed.
		}
		return false;
	}

	private boolean isJulUsingASingleConsoleHandlerAtMost() {
		java.util.logging.Logger rootLogger = java.util.logging.LogManager.getLogManager().getLogger("");
		Handler[] handlers = rootLogger.getHandlers();
		return handlers.length == 0 || (handlers.length == 1 && handlers[0] instanceof ConsoleHandler);
	}

	private boolean isLog4jLogManagerInstalled() {
		final String logManagerClassName = java.util.logging.LogManager.getLogManager().getClass().getName();
		return LOG4J_LOG_MANAGER.equals(logManagerClassName);
	}

	private boolean isLog4jBridgeHandlerAvailable() {
		return ClassUtils.isPresent(LOG4J_BRIDGE_HANDLER, getClassLoader());
	}

	private void removeLog4jBridgeHandler() {
		removeDefaultRootHandler();
		java.util.logging.Logger rootLogger = java.util.logging.LogManager.getLogManager().getLogger("");
		for (final Handler handler : rootLogger.getHandlers()) {
			if (handler instanceof Log4jBridgeHandler) {
				handler.close();
				rootLogger.removeHandler(handler);
			}
		}
	}

	private void removeDefaultRootHandler() {
		try {
			java.util.logging.Logger rootLogger = java.util.logging.LogManager.getLogManager().getLogger("");
			Handler[] handlers = rootLogger.getHandlers();
			if (handlers.length == 1 && handlers[0] instanceof ConsoleHandler) {
				rootLogger.removeHandler(handlers[0]);
			}
		}
		catch (Throwable ex) {
			// Ignore and continue
		}
	}

	@Override
	public void initialize(LoggingInitializationContext initializationContext, String configLocation, LogFile logFile) {
		LoggerContext loggerContext = getLoggerContext();
		if (isAlreadyInitialized(loggerContext)) {
			return;
		}
		Environment environment = initializationContext.getEnvironment();
		if (environment != null) {
			getLoggerContext().putObjectIfAbsent(ENVIRONMENT_KEY, environment);
			PropertiesUtil.getProperties().addPropertySource(new SpringEnvironmentPropertySource(environment));
		}
		loggerContext.getConfiguration().removeFilter(FILTER);
		super.initialize(initializationContext, configLocation, logFile);
		markAsInitialized(loggerContext);
	}

	@Override
	protected void loadDefaults(LoggingInitializationContext initializationContext, LogFile logFile) {
		if (logFile != null) {
			loadConfiguration(getPackagedConfigFile("log4j2-file.xml"), logFile, getOverrides(initializationContext));
		}
		else {
			loadConfiguration(getPackagedConfigFile("log4j2.xml"), logFile, getOverrides(initializationContext));
		}
	}

	private List<String> getOverrides(LoggingInitializationContext initializationContext) {
		BindResult<List<String>> overrides = Binder.get(initializationContext.getEnvironment())
				.bind("logging.log4j2.config.override", Bindable.listOf(String.class));
		return overrides.orElse(Collections.emptyList());
	}

	@Override
	protected void loadConfiguration(LoggingInitializationContext initializationContext, String location,
			LogFile logFile) {
		if (initializationContext != null) {
			applySystemProperties(initializationContext.getEnvironment(), logFile);
		}
		loadConfiguration(location, logFile, getOverrides(initializationContext));
	}

	/**
	 * Load the configuration from the given {@code location}, creating a composite using
	 * the configuration from the given {@code overrides}.
	 * @param location the location
	 * @param logFile log file configuration
	 * @param overrides the overriding locations
	 * @since 2.6.0
	 */
	protected void loadConfiguration(String location, LogFile logFile, List<String> overrides) {
		Assert.notNull(location, "Location must not be null");
		try {
			List<Configuration> configurations = new ArrayList<>();
			LoggerContext context = getLoggerContext();
			configurations.add(load(location, context));
			for (String override : overrides) {
				configurations.add(load(override, context));
			}
			Configuration configuration = (configurations.size() > 1) ? createComposite(configurations)
					: configurations.iterator().next();
			context.start(configuration);
		}
		catch (Exception ex) {
			throw new IllegalStateException("Could not initialize Log4J2 logging from " + location, ex);
		}
	}

	private Configuration load(String location, LoggerContext context) throws IOException {
		URL url = ResourceUtils.getURL(location);
		ConfigurationSource source = getConfigurationSource(url);
		return ConfigurationFactory.getInstance().getConfiguration(context, source);
	}

	private ConfigurationSource getConfigurationSource(URL url) throws IOException {
		if (FILE_PROTOCOL.equals(url.getProtocol())) {
			return new ConfigurationSource(url.openStream(), ResourceUtils.getFile(url));
		}
		AuthorizationProvider authorizationProvider = ConfigurationFactory
				.authorizationProvider(PropertiesUtil.getProperties());
		SslConfiguration sslConfiguration = url.getProtocol().equals("https")
				? SslConfigurationFactory.getSslConfiguration() : null;
		URLConnection connection = UrlConnectionFactory.createConnection(url, 0, sslConfiguration,
				authorizationProvider);
		return new ConfigurationSource(connection.getInputStream(), url, connection.getLastModified());
	}

	private CompositeConfiguration createComposite(List<Configuration> configurations) {
		return new CompositeConfiguration(configurations.stream().map(AbstractConfiguration.class::cast).toList());
	}

	@Override
	protected void reinitialize(LoggingInitializationContext initializationContext) {
		List<String> overrides = getOverrides(initializationContext);
		if (!CollectionUtils.isEmpty(overrides)) {
			reinitializeWithOverrides(overrides);
		}
		else {
			LoggerContext context = getLoggerContext();
			context.reconfigure();
		}
	}

	private void reinitializeWithOverrides(List<String> overrides) {
		LoggerContext context = getLoggerContext();
		Configuration base = context.getConfiguration();
		List<AbstractConfiguration> configurations = new ArrayList<>();
		configurations.add((AbstractConfiguration) base);
		for (String override : overrides) {
			try {
				configurations.add((AbstractConfiguration) load(override, context));
			}
			catch (IOException ex) {
				throw new RuntimeException("Failed to load overriding configuration from '" + override + "'", ex);
			}
		}
		CompositeConfiguration composite = new CompositeConfiguration(configurations);
		context.reconfigure(composite);
	}

	@Override
	public Set<LogLevel> getSupportedLogLevels() {
		return LEVELS.getSupported();
	}

	@Override
	public void setLogLevel(String loggerName, LogLevel logLevel) {
		setLogLevel(loggerName, LEVELS.convertSystemToNative(logLevel));
	}

	private void setLogLevel(String loggerName, Level level) {
		LoggerConfig logger = getLogger(loggerName);
		if (level == null) {
			clearLogLevel(loggerName, logger);
		}
		else {
			setLogLevel(loggerName, logger, level);
		}
		getLoggerContext().updateLoggers();
	}

	private void clearLogLevel(String loggerName, LoggerConfig logger) {
		if (logger instanceof LevelSetLoggerConfig) {
			getLoggerContext().getConfiguration().removeLogger(loggerName);
		}
		else {
			logger.setLevel(null);
		}
	}

	private void setLogLevel(String loggerName, LoggerConfig logger, Level level) {
		if (logger == null) {
			getLoggerContext().getConfiguration().addLogger(loggerName,
					new LevelSetLoggerConfig(loggerName, level, true));
		}
		else {
			logger.setLevel(level);
		}
	}

	@Override
	public List<LoggerConfiguration> getLoggerConfigurations() {
		List<LoggerConfiguration> result = new ArrayList<>();
		getAllLoggers().forEach((name, loggerConfig) -> result.add(convertLoggerConfig(name, loggerConfig)));
		result.sort(CONFIGURATION_COMPARATOR);
		return result;
	}

	@Override
	public LoggerConfiguration getLoggerConfiguration(String loggerName) {
		LoggerConfig loggerConfig = getAllLoggers().get(loggerName);
		return (loggerConfig != null) ? convertLoggerConfig(loggerName, loggerConfig) : null;
	}

	private Map<String, LoggerConfig> getAllLoggers() {
		Map<String, LoggerConfig> loggers = new LinkedHashMap<>();
		for (Logger logger : getLoggerContext().getLoggers()) {
			addLogger(loggers, logger.getName());
		}
		getLoggerContext().getConfiguration().getLoggers().keySet().forEach((name) -> addLogger(loggers, name));
		return loggers;
	}

	private void addLogger(Map<String, LoggerConfig> loggers, String name) {
		Configuration configuration = getLoggerContext().getConfiguration();
		while (name != null) {
			loggers.computeIfAbsent(name, configuration::getLoggerConfig);
			name = getSubName(name);
		}
	}

	private String getSubName(String name) {
		if (!StringUtils.hasLength(name)) {
			return null;
		}
		int nested = name.lastIndexOf('$');
		return (nested != -1) ? name.substring(0, nested) : NameUtil.getSubName(name);
	}

	private LoggerConfiguration convertLoggerConfig(String name, LoggerConfig loggerConfig) {
		if (loggerConfig == null) {
			return null;
		}
		LogLevel level = LEVELS.convertNativeToSystem(loggerConfig.getLevel());
		if (!StringUtils.hasLength(name) || LogManager.ROOT_LOGGER_NAME.equals(name)) {
			name = ROOT_LOGGER_NAME;
		}
		boolean isLoggerConfigured = loggerConfig.getName().equals(name);
		LogLevel configuredLevel = (isLoggerConfigured) ? level : null;
		return new LoggerConfiguration(name, configuredLevel, level);
	}

	@Override
	public Runnable getShutdownHandler() {
		return () -> getLoggerContext().stop();
	}

	@Override
	public void cleanUp() {
		if (isLog4jBridgeHandlerAvailable()) {
			removeLog4jBridgeHandler();
		}
		super.cleanUp();
		LoggerContext loggerContext = getLoggerContext();
		markAsUninitialized(loggerContext);
		loggerContext.getConfiguration().removeFilter(FILTER);
	}

	private LoggerConfig getLogger(String name) {
		boolean isRootLogger = !StringUtils.hasLength(name) || ROOT_LOGGER_NAME.equals(name);
		return findLogger(isRootLogger ? LogManager.ROOT_LOGGER_NAME : name);
	}

	private LoggerConfig findLogger(String name) {
		Configuration configuration = getLoggerContext().getConfiguration();
		if (configuration instanceof AbstractConfiguration abstractConfiguration) {
			return abstractConfiguration.getLogger(name);
		}
		return configuration.getLoggers().get(name);
	}

	private LoggerContext getLoggerContext() {
		return (LoggerContext) LogManager.getContext(false);
	}

	private boolean isAlreadyInitialized(LoggerContext loggerContext) {
		return LoggingSystem.class.getName().equals(loggerContext.getExternalContext());
	}

	private void markAsInitialized(LoggerContext loggerContext) {
		loggerContext.setExternalContext(LoggingSystem.class.getName());
	}

	private void markAsUninitialized(LoggerContext loggerContext) {
		loggerContext.setExternalContext(null);
	}

	/**
	 * Get the Spring {@link Environment} attached to the given {@link LoggerContext} or
	 * {@code null} if no environment is available.
	 * @param loggerContext the logger context
	 * @return the Spring {@link Environment} or {@code null}
	 * @since 3.0.0
	 */
	public static Environment getEnvironment(LoggerContext loggerContext) {
		return (Environment) ((loggerContext != null) ? loggerContext.getObject(ENVIRONMENT_KEY) : null);
	}

	/**
	 * {@link LoggingSystemFactory} that returns {@link Log4J2LoggingSystem} if possible.
	 */
	@Order(Ordered.LOWEST_PRECEDENCE)
	public static class Factory implements LoggingSystemFactory {

		private static final boolean PRESENT = ClassUtils
				.isPresent("org.apache.logging.log4j.core.impl.Log4jContextFactory", Factory.class.getClassLoader());

		@Override
		public LoggingSystem getLoggingSystem(ClassLoader classLoader) {
			if (PRESENT) {
				return new Log4J2LoggingSystem(classLoader);
			}
			return null;
		}

	}

	/**
	 * {@link LoggerConfig} used when the user has set a specific {@link Level}.
	 */
	private static class LevelSetLoggerConfig extends LoggerConfig {

		LevelSetLoggerConfig(String name, Level level, boolean additive) {
			super(name, level, additive);
		}

	}

}
