package eu.solven.cleanthat.code_provider.github.refs;

import java.util.concurrent.atomic.AtomicReference;

import org.kohsuke.github.GHBranch;
import org.kohsuke.github.GHRepository;

import eu.solven.cleanthat.code_provider.github.code_provider.AGithubSha1CodeProvider;
import eu.solven.cleanthat.codeprovider.ICodeProvider;
import eu.solven.cleanthat.jgit.JGitCodeProvider;

/**
 * An {@link ICodeProvider} for Github pull-requests
 *
 * @author Benoit Lacelle
 */
public class GithubBranchCodeProvider extends AGithubSha1CodeProvider {
	final GHBranch branch;

	final AtomicReference<JGitCodeProvider> localClone = new AtomicReference<>();

	public GithubBranchCodeProvider(String token, GHRepository repo, GHBranch branch) {
		super(token, repo);
		this.branch = branch;
	}

	@Override
	public String getHtmlUrl() {
		return "TODO Branch URL";
	}

	@Override
	public String getTitle() {
		return branch.getName();
	}

	@Override
	public String getSha1() {
		return branch.getSHA1();
	}

	@Override
	public String getRef() {
		return branch.getName();
	}

}
