package eu.solven.cleanthat.code_provider.github.refs;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import org.kohsuke.github.GHBranch;
import org.kohsuke.github.GHCommit;
import org.kohsuke.github.GHFileNotFoundException;
import org.kohsuke.github.GHIssueState;
import org.kohsuke.github.GHPullRequest;
import org.kohsuke.github.GHRef;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GHTree;
import org.kohsuke.github.GitHub;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Charsets;
import com.google.common.io.CharStreams;

import eu.solven.cleanthat.any_language.ACodeCleaner;
import eu.solven.cleanthat.code_provider.github.event.GithubAndToken;
import eu.solven.cleanthat.code_provider.github.event.pojo.GitRepoBranchSha1;
import eu.solven.cleanthat.code_provider.github.event.pojo.HeadAndOptionalBase;
import eu.solven.cleanthat.code_provider.github.event.pojo.IExternalWebhookRelevancyResult;
import eu.solven.cleanthat.codeprovider.ICodeProvider;
import eu.solven.cleanthat.codeprovider.ICodeProviderWriter;
import eu.solven.cleanthat.formatter.CodeFormatResult;
import eu.solven.cleanthat.formatter.ICodeProviderFormatter;
import eu.solven.cleanthat.git_abstraction.GithubFacade;
import eu.solven.cleanthat.git_abstraction.GithubRepositoryFacade;
import eu.solven.cleanthat.github.CleanthatRefFilterProperties;
import eu.solven.cleanthat.github.CleanthatRepositoryProperties;
import eu.solven.cleanthat.utils.ResultOrError;

/**
 * Default for {@link IGithubRefCleaner}
 *
 * @author Benoit Lacelle
 */
public class GithubRefCleaner extends ACodeCleaner implements IGithubRefCleaner {
	private static final Logger LOGGER = LoggerFactory.getLogger(GithubRefCleaner.class);

	private static final String REF_DOMAIN_CLEANTHAT = "cleanthat";

	public static final String PREFIX_REF_CLEANTHAT =
			CleanthatRefFilterProperties.BRANCHES_PREFIX + REF_DOMAIN_CLEANTHAT + "/";
	public static final String REF_NAME_CONFIGURE = PREFIX_REF_CLEANTHAT + "configure";

	public static final String PREFIX_REF_CLEANTHAT_TMPHEAD = PREFIX_REF_CLEANTHAT + "headfor-";
	public static final String PREFIX_REF_CLEANTHAT_MANUAL = PREFIX_REF_CLEANTHAT + "manual-";

	final GithubAndToken githubAndToken;

	public GithubRefCleaner(List<ObjectMapper> objectMappers,
			ICodeProviderFormatter formatterProvider,
			GithubAndToken githubAndToken) {
		super(objectMappers, formatterProvider);
		this.githubAndToken = githubAndToken;
	}

	// We may have no ref to clean (e.g. there is no cleanthat configuration, or the ref is excluded)
	// We may have to clean current ref (e.g. a PR is open, and we want to clean the PR head)
	// We may have to clean a different ref (e.g. a push to the main branch needs to be cleaned through a PR)
	@SuppressWarnings("PMD.CognitiveComplexity")
	@Override
	public Optional<HeadAndOptionalBase> prepareRefToClean(IExternalWebhookRelevancyResult result,
			GitRepoBranchSha1 theRef,
			// There can be multiple eventBaseBranches in case of push events
			Set<String> eventBaseBranches) {
		ICodeProvider codeProvider = getCodeProviderForRef(theRef);
		ResultOrError<CleanthatRepositoryProperties, String> optConfig = loadAndCheckConfiguration(codeProvider);

		if (optConfig.getOptError().isPresent()) {
			return Optional.empty();
		}

		CleanthatRepositoryProperties properties = optConfig.getOptResult().get();

		// TODO If the configuration changed, trigger full-clean only if the change is an effective change (and not just
		// json/yaml/etc formatting)
		migrateConfigurationCode(properties);
		List<String> cleanableBranchRegexes = properties.getMeta().getRefs().getBranches();

		Optional<String> optBaseMatchingRule = cleanableBranchRegexes.stream().filter(cleanableBranchRegex -> {
			Optional<String> matchingBase = eventBaseBranches.stream().filter(base -> {
				return Pattern.matches(cleanableBranchRegex, base);
			}).findAny();

			if (matchingBase.isEmpty()) {
				LOGGER.info("Not a single base with open RR matches cleanableBranchRegex={}", cleanableBranchRegex);
				return false;
			} else {
				LOGGER.info("We have a match for ruleBranch={} eventBaseBranch={}",
						cleanableBranchRegex,
						matchingBase.get());
			}

			return true;
		}).findAny();

		String fullRef = theRef.getRef();
		if (optBaseMatchingRule.isPresent()) {
			// The base is cleanable: we are allowed to clean its head in-place
			String baseMatchingRule = optBaseMatchingRule.get();
			if (result.isReviewRequestOpen()) {
				LOGGER.info(
						"We will clean {} in place as this event is due to a RR (re)open event with cleanable base (rule={})",
						fullRef,
						baseMatchingRule);
			} else {
				LOGGER.info(
						"We will clean {} in place as this event is due to a push over a branch, itself head of a RR with a cleanable base (rule={})",
						fullRef,
						baseMatchingRule);
			}
			GitRepoBranchSha1 head = new GitRepoBranchSha1(theRef.getRepoName(), fullRef, theRef.getSha());
			return Optional.of(new HeadAndOptionalBase(head, result.optBaseRef()));
		}

		Optional<String> optHeadMatchingRule = cleanableBranchRegexes.stream().filter(cleanableBranchRegex -> {
			return Pattern.matches(cleanableBranchRegex, fullRef);
		}).findAny();

		if (optHeadMatchingRule.isPresent()) {
			LOGGER.info(
					"We have an event over a branch which is cleanable, but not head of an open PR to a cleanable base: we shall clean this through a new PR");

			// We never clean inplace: we'll have to open a dedicated ReviewRequest if necessary
			String newBranchRef = prepareRefNameForHead(fullRef);
			// We may open a branch later if it appears this branch is relevant
			// String refToClean = codeProvider.openBranch(ref);
			// BEWARE we do not open the branch right now: we wait to detect at least one fail is relevant to be clean
			// In case of concurrent events, we may end opening multiple PR to clean the same branch
			// TODO Should we handle this specifically when opening the actual branch?
			GitRepoBranchSha1 head = new GitRepoBranchSha1(theRef.getRepoName(), newBranchRef, theRef.getSha());
			return Optional.of(new HeadAndOptionalBase(head, Optional.of(theRef)));
		} else {
			LOGGER.info("This branch seems not cleanable: {}. Regex: {}. eventBaseBranches: {}",
					fullRef,
					cleanableBranchRegexes,
					eventBaseBranches);
			return Optional.empty();
		}
	}

	/**
	 * @return a new/unique reference, useful when opening a branch to clean a cleanable branch.
	 */
	public String prepareRefNameForHead(String baseToClean) {
		return PREFIX_REF_CLEANTHAT_TMPHEAD + baseToClean.replace('/', '_').replace('-', '_') + "-" + UUID.randomUUID();
	}

	public ICodeProvider getCodeProviderForRef(GitRepoBranchSha1 theRef) {
		String ref = theRef.getRef();

		try {
			String repoName = theRef.getRepoName();
			GithubFacade facade = new GithubFacade(githubAndToken.getGithub(), repoName);
			GHRef refObject = facade.getRef(ref);
			return new GithubRefCodeProvider(githubAndToken.getToken(), facade.getRepository(), refObject);
		} catch (IOException e) {
			throw new UncheckedIOException("Issue with ref: " + ref, e);
		}
	}

	@Override
	public CodeFormatResult formatRefDiff(GHRepository repo, GHRef base, Supplier<GHRef> headSupplier) {
		// TODO Get the head lazily
		GHRef head = headSupplier.get();

		LOGGER.info("Base: {} Head: {}", base.getRef(), head.getRef());
		ICodeProviderWriter codeProvider = new GithubRefDiffCodeProvider(githubAndToken.getToken(), repo, base, head);
		return formatCodeGivenConfig(codeProvider, false);
	}

	@Override
	public CodeFormatResult formatRef(GHRepository repo, Supplier<GHRef> refSupplier) {
		// TODO Get the head lazily
		GHRef ref = refSupplier.get();

		ICodeProviderWriter codeProvider = new GithubRefCodeProvider(githubAndToken.getToken(), repo, ref);
		LOGGER.info("Ref: {}", codeProvider.getHtmlUrl());
		return formatCodeGivenConfig(codeProvider, false);
	}

	public void openPRWithCleanThatStandardConfiguration(GitHub userToServerGithub, GHBranch defaultBranch) {
		GHRepository repo = defaultBranch.getOwner();
		String refName = REF_NAME_CONFIGURE;
		String fullRefName = GithubFacade.toFullGitRef(refName);
		boolean refAlreadyExists;
		Optional<GHRef> refToPR;
		try {
			try {
				refToPR = Optional.of(new GithubRepositoryFacade(repo).getRef(fullRefName));
				LOGGER.info("There is already a ref: " + fullRefName);
				refAlreadyExists = true;
			} catch (GHFileNotFoundException e) {
				LOGGER.trace("There is not yet a ref: " + fullRefName, e);
				LOGGER.info("There is not yet a ref: " + fullRefName);
				refAlreadyExists = false;
				refToPR = Optional.empty();
			}
		} catch (IOException e) {
			// TODO If 401, it probably means the Installation is not allowed to see/modify given repository
			throw new UncheckedIOException(e);
		}
		try {
			if (refAlreadyExists) {
				LOGGER.info(
						"There is already a ref about to introduce a cleanthat default configuration. Do not open a new PR (url={})",
						refToPR.get().getUrl().toExternalForm());
				repo.listPullRequests(GHIssueState.ALL).forEach(pr -> {
					if (refName.equals(pr.getHead().getRef())) {
						LOGGER.info("Related PR: {}", pr.getHtmlUrl());
					}
				});
			} else {
				GHCommit commit = commitConfig(defaultBranch, repo);
				refToPR = Optional.of(repo.createRef(fullRefName, commit.getSHA1()));
				boolean force = false;
				refToPR.get().updateTo(commit.getSHA1(), force);
				// Let's follow Renovate and its configuration PR
				// https://github.com/solven-eu/agilea/pull/1
				String body = readResource("/templates/onboarding-body.md");
				body = body.replaceAll(Pattern.quote("${REPO_FULL_NAME}"), repo.getFullName());
				// Issue using '/' in the base, while renovate succeed naming branches: 'renovate/configure'
				// TODO What is this issue exactly? We seem to success naming our ref 'cleanthat/configure'
				GHPullRequest pr = repo.createPullRequest("Configure CleanThat",
						refToPR.get().getRef(),
						defaultBranch.getName(),
						body,
						true,
						false);
				LOGGER.info("Open PR: {}", pr.getHtmlUrl());
			}
		} catch (IOException e) {
			// TODO If 401, it probably means the Installation is not allowed to modify given repo
			throw new UncheckedIOException(e);
		}
	}

	private GHCommit commitConfig(GHBranch defaultBranch, GHRepository repo) throws IOException {
		// Guess Java version: https://github.com/solven-eu/spring-boot/blob/master/buildSrc/build.gradle#L13
		// Detect usage of Checkstyle:
		// https://github.com/solven-eu/spring-boot/blob/master/buildSrc/build.gradle#L35
		// Code formatting: https://github.com/solven-eu/spring-boot/blob/master/buildSrc/build.gradle#L17
		// https://github.com/spring-io/spring-javaformat/blob/master/src/checkstyle/checkstyle.xml
		// com.puppycrawl.tools.checkstyle.checks.imports.UnusedImportsCheck
		String exampleConfig = readResource("/standard-configurations/standard-java-11-spring");
		GHTree createTree = repo.createTree()
				.baseTree(defaultBranch.getSHA1())
				.add("cleanthat.json", exampleConfig, false)
				.create();
		GHCommit commit = GithubPRCodeProvider.prepareCommit(repo)
				.message("Add default Cleanthat configuration")
				.parent(defaultBranch.getSHA1())
				.tree(createTree.getSha())
				.create();
		return commit;
	}

	private String readResource(String path) {
		String body;
		try (InputStreamReader reader =
				new InputStreamReader(new ClassPathResource(path).getInputStream(), Charsets.UTF_8)) {
			body = CharStreams.toString(reader);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		return body;
	}
}
