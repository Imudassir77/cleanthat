package eu.solven.cleanthat.code_provider.github.refs;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.kohsuke.github.GHFileNotFoundException;
import org.kohsuke.github.GHPullRequest;
import org.kohsuke.github.GHPullRequestFileDetail;
import org.kohsuke.github.GHRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import eu.solven.cleanthat.code_provider.github.code_provider.AGithubCodeProvider;
import eu.solven.cleanthat.codeprovider.DummyCodeProviderFile;
import eu.solven.cleanthat.codeprovider.ICodeProvider;
import eu.solven.cleanthat.codeprovider.ICodeProviderFile;
import eu.solven.cleanthat.codeprovider.ICodeProviderWriter;
import eu.solven.cleanthat.codeprovider.IListOnlyModifiedFiles;

/**
 * An {@link ICodeProvider} for Github pull-requests
 *
 * @author Benoit Lacelle
 */
public class GithubPRCodeProvider extends AGithubCodeProvider implements IListOnlyModifiedFiles, ICodeProviderWriter {

	private static final Logger LOGGER = LoggerFactory.getLogger(GithubPRCodeProvider.class);

	final String token;
	final GHPullRequest pr;

	public GithubPRCodeProvider(String token, GHPullRequest pr) {
		this.token = token;
		this.pr = pr;
	}

	@Override
	public void listFiles(Consumer<ICodeProviderFile> consumer) throws IOException {
		pr.listFiles().forEach(prFile -> {
			if ("deleted".equals(prFile.getStatus())) {
				LOGGER.debug("Skip a deleted file: {}", prFile.getFilename());
			} else {
				consumer.accept(new DummyCodeProviderFile(prFile.getFilename(), prFile));
			}
		});
	}

	// @Override
	// public boolean deprecatedFileIsRemoved(Object file) {
	// return "removed".equals(((GHPullRequestFileDetail) file).getStatus());
	// }

	public static String loadContent(GHPullRequest pr, String filename) throws IOException {
		GHRepository repository = pr.getRepository();
		String sha1 = pr.getHead().getSha();
		return loadContent(repository, filename, sha1);
	}

	@Override
	public String getHtmlUrl() {
		return pr.getHtmlUrl().toExternalForm();
	}

	@Override
	public String getTitle() {
		return pr.getTitle();
	}

	@Override
	public void persistChanges(Map<String, String> pathToMutatedContent,
			List<String> prComments,
			Collection<String> prLabels
	// ,
	// Optional<String> targetBranch
	) {
		// if (targetBranch.isPresent()) {
		// throw new UnsupportedOperationException("TODO");
		// }

		String refName = pr.getHead().getRef();
		GHRepository repo = pr.getRepository();

		String fullRefName = "heads/" + refName;
		LOGGER.debug("Processing head={} for pr={}", fullRefName, pr);
		commitIntoRef(pathToMutatedContent, prComments, repo, fullRefName);
	}

	@Override
	public String deprecatedLoadContent(Object file) throws IOException {
		return loadContent(pr, ((GHPullRequestFileDetail) file).getFilename());
	}

	@Override
	public String deprecatedGetFilePath(Object file) {
		return ((GHPullRequestFileDetail) file).getFilename();
	}

	@Override
	public Optional<String> loadContentForPath(String path) throws IOException {
		try {
			return Optional.of(loadContent(pr.getRepository(), path, pr.getHead().getSha()));
		} catch (GHFileNotFoundException e) {
			LOGGER.trace("We miss: {}", path, e);
			LOGGER.debug("We miss: {}", path);
			return Optional.empty();
		}
	}

	@Override
	public String getRepoUri() {
		return pr.getRepository().getGitTransportUrl();
	}

	// @Override
	public Git makeGitRepo() {
		Path tmpDir;
		try {
			tmpDir = Files.createTempDirectory("cleanthat-clone");
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}

		try {
			GHRepository repo = pr.getRepository();
			return Git.cloneRepository()
					.setURI(repo.getGitTransportUrl())
					.setDirectory(tmpDir.toFile())
					.setBranch(pr.getHead().getRef())
					.setCloneAllBranches(false)
					.setCloneSubmodules(false)
					.call();
		} catch (GitAPIException e) {
			throw new IllegalArgumentException(e);
		}
	}
}
