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

package org.springframework.boot.logging.logback;

import java.net.URL;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.logging.ConsoleHandler;
import java.util.logging.Handler;
import java.util.logging.LogManager;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.classic.jul.LevelChangePropagator;
import ch.qos.logback.classic.turbo.TurboFilter;
import ch.qos.logback.classic.util.ContextInitializer;
import ch.qos.logback.core.joran.spi.JoranException;
import ch.qos.logback.core.spi.FilterReply;
import ch.qos.logback.core.status.OnConsoleStatusListener;
import ch.qos.logback.core.status.Status;
import ch.qos.logback.core.util.StatusListenerConfigHelper;
import org.slf4j.ILoggerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.Marker;
import org.slf4j.bridge.SLF4JBridgeHandler;

import org.springframework.aot.AotDetector;
import org.springframework.beans.factory.aot.BeanFactoryInitializationAotContribution;
import org.springframework.beans.factory.aot.BeanFactoryInitializationAotProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.logging.AbstractLoggingSystem;
import org.springframework.boot.logging.LogFile;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggerConfiguration;
import org.springframework.boot.logging.LoggingInitializationContext;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.boot.logging.LoggingSystemFactory;
import org.springframework.boot.logging.LoggingSystemProperties;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.ResourceUtils;
import org.springframework.util.StringUtils;

/**
 * {@link LoggingSystem} for <a href="https://logback.qos.ch">logback</a>.
 *
 * @author Phillip Webb
 * @author Dave Syer
 * @author Andy Wilkinson
 * @author Ben Hale
 * @since 1.0.0
 */
public class LogbackLoggingSystem extends AbstractLoggingSystem implements BeanFactoryInitializationAotProcessor {

	private static final String BRIDGE_HANDLER = "org.slf4j.bridge.SLF4JBridgeHandler";

	private static final String CONFIGURATION_FILE_PROPERTY = "logback.configurationFile";

	private static final LogLevels<Level> LEVELS = new LogLevels<>();

	static {
		LEVELS.map(LogLevel.TRACE, Level.TRACE);
		LEVELS.map(LogLevel.TRACE, Level.ALL);
		LEVELS.map(LogLevel.DEBUG, Level.DEBUG);
		LEVELS.map(LogLevel.INFO, Level.INFO);
		LEVELS.map(LogLevel.WARN, Level.WARN);
		LEVELS.map(LogLevel.ERROR, Level.ERROR);
		LEVELS.map(LogLevel.FATAL, Level.ERROR);
		LEVELS.map(LogLevel.OFF, Level.OFF);
	}

	private static final TurboFilter FILTER = new TurboFilter() {

		@Override
		public FilterReply decide(Marker marker, ch.qos.logback.classic.Logger logger, Level level, String format,
				Object[] params, Throwable t) {
			return FilterReply.DENY;
		}

	};

	public LogbackLoggingSystem(ClassLoader classLoader) {
		super(classLoader);
	}

	@Override
	public LoggingSystemProperties getSystemProperties(ConfigurableEnvironment environment) {
		return new LogbackLoggingSystemProperties(environment);
	}

	@Override
	protected String[] getStandardConfigLocations() {
		return new String[] { "logback-test.groovy", "logback-test.xml", "logback.groovy", "logback.xml" };
	}

	@Override
	public void beforeInitialize() {
		LoggerContext loggerContext = getLoggerContext();
		if (isAlreadyInitialized(loggerContext)) {
			return;
		}
		super.beforeInitialize();
		configureJdkLoggingBridgeHandler();
		loggerContext.getTurboFilterList().add(FILTER);
	}

	private void configureJdkLoggingBridgeHandler() {
		try {
			if (isBridgeJulIntoSlf4j()) {
				removeJdkLoggingBridgeHandler();
				SLF4JBridgeHandler.install();
			}
		}
		catch (Throwable ex) {
			// Ignore. No java.util.logging bridge is installed.
		}
	}

	private boolean isBridgeJulIntoSlf4j() {
		return isBridgeHandlerAvailable() && isJulUsingASingleConsoleHandlerAtMost();
	}

	private boolean isBridgeHandlerAvailable() {
		return ClassUtils.isPresent(BRIDGE_HANDLER, getClassLoader());
	}

	private boolean isJulUsingASingleConsoleHandlerAtMost() {
		java.util.logging.Logger rootLogger = LogManager.getLogManager().getLogger("");
		Handler[] handlers = rootLogger.getHandlers();
		return handlers.length == 0 || (handlers.length == 1 && handlers[0] instanceof ConsoleHandler);
	}

	private void removeJdkLoggingBridgeHandler() {
		try {
			removeDefaultRootHandler();
			SLF4JBridgeHandler.uninstall();
		}
		catch (Throwable ex) {
			// Ignore and continue
		}
	}

	private void removeDefaultRootHandler() {
		try {
			java.util.logging.Logger rootLogger = LogManager.getLogManager().getLogger("");
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
		if (!initializeFromAotGeneratedArtifactsIfPossible(initializationContext, logFile)) {
			super.initialize(initializationContext, configLocation, logFile);
		}
		loggerContext.getTurboFilterList().remove(FILTER);
		markAsInitialized(loggerContext);
		if (StringUtils.hasText(System.getProperty(CONFIGURATION_FILE_PROPERTY))) {
			getLogger(LogbackLoggingSystem.class.getName()).warn("Ignoring '" + CONFIGURATION_FILE_PROPERTY
					+ "' system property. Please use 'logging.config' instead.");
		}
	}

	private boolean initializeFromAotGeneratedArtifactsIfPossible(LoggingInitializationContext initializationContext,
			LogFile logFile) {
		if (!AotDetector.useGeneratedArtifacts()) {
			return false;
		}
		if (initializationContext != null) {
			applySystemProperties(initializationContext.getEnvironment(), logFile);
		}
		LoggerContext loggerContext = getLoggerContext();
		stopAndReset(loggerContext);
		SpringBootJoranConfigurator configurator = new SpringBootJoranConfigurator(initializationContext);
		configurator.setContext(loggerContext);
		return configurator.configureUsingAotGeneratedArtifacts();
	}

	@Override
	protected void loadDefaults(LoggingInitializationContext initializationContext, LogFile logFile) {
		LoggerContext context = getLoggerContext();
		stopAndReset(context);
		boolean debug = Boolean.getBoolean("logback.debug");
		if (debug) {
			StatusListenerConfigHelper.addOnConsoleListenerInstance(context, new OnConsoleStatusListener());
		}
		Environment environment = initializationContext.getEnvironment();
		// Apply system properties directly in case the same JVM runs multiple apps
		new LogbackLoggingSystemProperties(environment, context::putProperty).apply(logFile);
		LogbackConfigurator configurator = debug ? new DebugLogbackConfigurator(context)
				: new LogbackConfigurator(context);
		new DefaultLogbackConfiguration(logFile).apply(configurator);
		context.setPackagingDataEnabled(true);
	}

	@Override
	protected void loadConfiguration(LoggingInitializationContext initializationContext, String location,
			LogFile logFile) {
		if (initializationContext != null) {
			applySystemProperties(initializationContext.getEnvironment(), logFile);
		}
		LoggerContext loggerContext = getLoggerContext();
		stopAndReset(loggerContext);
		try {
			configureByResourceUrl(initializationContext, loggerContext, ResourceUtils.getURL(location));
		}
		catch (Exception ex) {
			throw new IllegalStateException("Could not initialize Logback logging from " + location, ex);
		}
		List<Status> statuses = loggerContext.getStatusManager().getCopyOfStatusList();
		StringBuilder errors = new StringBuilder();
		for (Status status : statuses) {
			if (status.getLevel() == Status.ERROR) {
				errors.append((errors.length() > 0) ? String.format("%n") : "");
				errors.append(status.toString());
			}
		}
		if (errors.length() > 0) {
			throw new IllegalStateException(String.format("Logback configuration error detected: %n%s", errors));
		}
	}

	private void configureByResourceUrl(LoggingInitializationContext initializationContext, LoggerContext loggerContext,
			URL url) throws JoranException {
		if (url.toString().endsWith("xml")) {
			JoranConfigurator configurator = new SpringBootJoranConfigurator(initializationContext);
			configurator.setContext(loggerContext);
			configurator.doConfigure(url);
		}
		else {
			new ContextInitializer(loggerContext).configureByResource(url);
		}
	}

	private void stopAndReset(LoggerContext loggerContext) {
		loggerContext.stop();
		loggerContext.reset();
		if (isBridgeHandlerInstalled()) {
			addLevelChangePropagator(loggerContext);
		}
	}

	private boolean isBridgeHandlerInstalled() {
		if (!isBridgeHandlerAvailable()) {
			return false;
		}
		java.util.logging.Logger rootLogger = LogManager.getLogManager().getLogger("");
		Handler[] handlers = rootLogger.getHandlers();
		return handlers.length == 1 && handlers[0] instanceof SLF4JBridgeHandler;
	}

	private void addLevelChangePropagator(LoggerContext loggerContext) {
		LevelChangePropagator levelChangePropagator = new LevelChangePropagator();
		levelChangePropagator.setResetJUL(true);
		levelChangePropagator.setContext(loggerContext);
		loggerContext.addListener(levelChangePropagator);
	}

	@Override
	public void cleanUp() {
		LoggerContext context = getLoggerContext();
		markAsUninitialized(context);
		super.cleanUp();
		if (isBridgeHandlerAvailable()) {
			removeJdkLoggingBridgeHandler();
		}
		context.getStatusManager().clear();
		context.getTurboFilterList().remove(FILTER);
	}

	@Override
	protected void reinitialize(LoggingInitializationContext initializationContext) {
		getLoggerContext().reset();
		getLoggerContext().getStatusManager().clear();
		loadConfiguration(initializationContext, getSelfInitializationConfig(), null);
	}

	@Override
	public List<LoggerConfiguration> getLoggerConfigurations() {
		List<LoggerConfiguration> result = new ArrayList<>();
		for (ch.qos.logback.classic.Logger logger : getLoggerContext().getLoggerList()) {
			result.add(getLoggerConfiguration(logger));
		}
		result.sort(CONFIGURATION_COMPARATOR);
		return result;
	}

	@Override
	public LoggerConfiguration getLoggerConfiguration(String loggerName) {
		String name = getLoggerName(loggerName);
		LoggerContext loggerContext = getLoggerContext();
		return getLoggerConfiguration(loggerContext.exists(name));
	}

	private String getLoggerName(String name) {
		if (!StringUtils.hasLength(name) || Logger.ROOT_LOGGER_NAME.equals(name)) {
			return ROOT_LOGGER_NAME;
		}
		return name;
	}

	private LoggerConfiguration getLoggerConfiguration(ch.qos.logback.classic.Logger logger) {
		if (logger == null) {
			return null;
		}
		LogLevel level = LEVELS.convertNativeToSystem(logger.getLevel());
		LogLevel effectiveLevel = LEVELS.convertNativeToSystem(logger.getEffectiveLevel());
		String name = getLoggerName(logger.getName());
		return new LoggerConfiguration(name, level, effectiveLevel);
	}

	@Override
	public Set<LogLevel> getSupportedLogLevels() {
		return LEVELS.getSupported();
	}

	@Override
	public void setLogLevel(String loggerName, LogLevel level) {
		ch.qos.logback.classic.Logger logger = getLogger(loggerName);
		if (logger != null) {
			logger.setLevel(LEVELS.convertSystemToNative(level));
		}
	}

	@Override
	public Runnable getShutdownHandler() {
		return () -> getLoggerContext().stop();
	}

	private ch.qos.logback.classic.Logger getLogger(String name) {
		LoggerContext factory = getLoggerContext();
		return factory.getLogger(getLoggerName(name));
	}

	private LoggerContext getLoggerContext() {
		ILoggerFactory factory = LoggerFactory.getILoggerFactory();
		Assert.isInstanceOf(LoggerContext.class, factory,
				() -> String.format(
						"LoggerFactory is not a Logback LoggerContext but Logback is on "
								+ "the classpath. Either remove Logback or the competing "
								+ "implementation (%s loaded from %s). If you are using "
								+ "WebLogic you will need to add 'org.slf4j' to "
								+ "prefer-application-packages in WEB-INF/weblogic.xml",
						factory.getClass(), getLocation(factory)));
		return (LoggerContext) factory;
	}

	private Object getLocation(ILoggerFactory factory) {
		try {
			ProtectionDomain protectionDomain = factory.getClass().getProtectionDomain();
			CodeSource codeSource = protectionDomain.getCodeSource();
			if (codeSource != null) {
				return codeSource.getLocation();
			}
		}
		catch (SecurityException ex) {
			// Unable to determine location
		}
		return "unknown location";
	}

	private boolean isAlreadyInitialized(LoggerContext loggerContext) {
		return loggerContext.getObject(LoggingSystem.class.getName()) != null;
	}

	private void markAsInitialized(LoggerContext loggerContext) {
		loggerContext.putObject(LoggingSystem.class.getName(), new Object());
	}

	private void markAsUninitialized(LoggerContext loggerContext) {
		loggerContext.removeObject(LoggingSystem.class.getName());
	}

	@Override
	public BeanFactoryInitializationAotContribution processAheadOfTime(ConfigurableListableBeanFactory beanFactory) {
		String key = BeanFactoryInitializationAotContribution.class.getName();
		LoggerContext context = getLoggerContext();
		BeanFactoryInitializationAotContribution contribution = (BeanFactoryInitializationAotContribution) context
				.getObject(key);
		context.removeObject(key);
		return contribution;
	}

	/**
	 * {@link LoggingSystemFactory} that returns {@link LogbackLoggingSystem} if possible.
	 */
	@Order(Ordered.LOWEST_PRECEDENCE)
	public static class Factory implements LoggingSystemFactory {

		private static final boolean PRESENT = ClassUtils.isPresent("ch.qos.logback.classic.LoggerContext",
				Factory.class.getClassLoader());

		@Override
		public LoggingSystem getLoggingSystem(ClassLoader classLoader) {
			if (PRESENT) {
				return new LogbackLoggingSystem(classLoader);
			}
			return null;
		}

	}

}
