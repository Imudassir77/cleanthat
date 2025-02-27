/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package eu.solven.cleanthat.language.java.eclipse;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

import org.eclipse.jdt.core.JavaCore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.xml.sax.SAXException;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;

import eu.solven.cleanthat.codeprovider.ICodeProvider;
import eu.solven.cleanthat.language.CleanthatUrlLoader;
import eu.solven.cleanthat.language.ILanguageProperties;
import eu.solven.cleanthat.language.java.eclipse.revelc.ConfigReadException;
import eu.solven.cleanthat.language.java.eclipse.revelc.ConfigReader;
import lombok.Data;

/**
 * Load the configuration. It is useful to be cached, as it may rely on an external {@link URL}
 *
 * @author Benoit Lacelle
 */
@Data
public class EclipseJavaFormatterConfiguration {
	private static final Logger LOGGER = LoggerFactory.getLogger(EclipseJavaFormatterConfiguration.class);

	private static final String KEY_URL = "url";

	private final Map<String, String> settings;

	public EclipseJavaFormatterConfiguration(Map<String, String> settings) {
		// Sorted for human-friendliness
		this.settings = ImmutableMap.copyOf(new TreeMap<>(settings));
	}

	public static EclipseJavaFormatterConfiguration load(ICodeProvider codeProvider,
			ILanguageProperties languageProperties,
			EclipseJavaFormatterProcessorProperties processorConfig) {
		String javaConfigFile = processorConfig.getUrl();

		// Eclipse default
		if (Strings.isNullOrEmpty(javaConfigFile)) {
			LOGGER.info("There is no {}. Switching to default formatting", KEY_URL);
			// https://github.com/revelc/formatter-maven-plugin/blob/master/src/main/java/net/revelc/code/formatter/FormatterMojo.java#L689
			// { "1.3", "1.4", "1.5", "1.6", "1.7", "1.8", "9", "10", "11" }
			String jdkVersion = languageProperties.getLanguageVersion();
			// if (optJdkVersion.isEmpty()) {
			// LOGGER.warn("No value for {}. Defaulted to: {}", KEY_JDK_VERSION, DEFAULT_JDK_VERSION);
			// }
			// String jdkVersion = optJdkVersion.orElse();
			Map<String, String> settings = new LinkedHashMap<>();
			settings.put(JavaCore.COMPILER_SOURCE, jdkVersion);
			settings.put(JavaCore.COMPILER_COMPLIANCE, jdkVersion);
			settings.put(JavaCore.COMPILER_CODEGEN_TARGET_PLATFORM, jdkVersion);

			return new EclipseJavaFormatterConfiguration(settings);
		} else {
			LOGGER.info("Loading Eclipse java formatting configuration from {}", javaConfigFile);

			Resource resource = CleanthatUrlLoader.loadUrl(codeProvider, javaConfigFile);

			try {
				return loadResource(resource);
			} catch (RuntimeException e) {
				throw new RuntimeException("Issue processing: " + javaConfigFile, e);
			}
		}
	}

	public static EclipseJavaFormatterConfiguration loadResource(Resource resource) {
		try (InputStream is = resource.getInputStream()) {
			try {
				Map<String, String> settings = new ConfigReader().read(is);
				return new EclipseJavaFormatterConfiguration(settings);
			} catch (SAXException | ConfigReadException e) {
				throw new RuntimeException("Issue parsing config", e);
			}
		} catch (MalformedURLException e) {
			throw new IllegalArgumentException("Invalid java.config_uri: + javaConfigFile", e);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
