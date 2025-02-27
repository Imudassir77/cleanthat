package eu.solven.cleanthat.mvn;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

/**
 * The mojo of the mvn plugin
 * 
 * @author Benoit Lacelle
 *
 */
// https://maven.apache.org/guides/plugin/guide-java-plugin-development.html
public abstract class ACleanThatMojo extends AbstractMojo {
	// http://maven.apache.org/ref/3.1.1/maven-core/apidocs/org/apache/maven/plugin/PluginParameterExpressionEvaluator.html
	@Parameter(defaultValue = "${project}", required = true, readonly = true)
	private MavenProject project;

	@Parameter(defaultValue = "${session}", required = true, readonly = true)
	private MavenSession session;

	// https://stackoverflow.com/questions/3084629/finding-the-root-directory-of-a-multi-module-maven-reactor-project
	@Parameter(property = "cleanthat.configPath", defaultValue = "${maven.multiModuleProjectDirectory}/cleanthat.yaml")
	private String configPath;

	@Parameter(property = "cleanthat.configUrl")
	private String configUrl;

	// Useful to check what are the expected impacts without actually changing project files
	@Parameter(property = "cleanthat.dryRun", defaultValue = "false")
	private boolean dryRun;

	/**
	 * Runs the plugin only if the current project is the execution root.
	 *
	 * This is helpful, if the plugin is defined in a profile and should only run once to download a shared file.
	 */
	// https://github.com/maven-download-plugin/maven-download-plugin/blob/master/src/main/java/com/googlecode/download/maven/plugin/internal/WGet.java
	@Parameter(property = "runOnlyAtRoot", defaultValue = "false")
	private boolean runOnlyAtRoot;

	public MavenProject getProject() {
		return project;
	}

	public MavenSession getSession() {
		return session;
	}

	public String getConfigPath() {
		return configPath;
	}

	public String getConfigUrl() {
		return configUrl;
	}

	public boolean isDryRun() {
		return dryRun;
	}

	public boolean isRunOnlyAtRoot() {
		return runOnlyAtRoot;
	}
}