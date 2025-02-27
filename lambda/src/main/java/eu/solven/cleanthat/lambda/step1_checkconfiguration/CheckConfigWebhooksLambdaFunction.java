package eu.solven.cleanthat.lambda.step1_checkconfiguration;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;

import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.fasterxml.jackson.databind.ObjectMapper;

import eu.solven.cleanthat.code_provider.github.event.ICodeCleanerFactory;
import eu.solven.cleanthat.code_provider.github.event.IGithubWebhookHandler;
import eu.solven.cleanthat.code_provider.github.event.IGithubWebhookHandlerFactory;
import eu.solven.cleanthat.code_provider.github.event.pojo.CleanThatWebhookEvent;
import eu.solven.cleanthat.code_provider.github.event.pojo.GitRepoBranchSha1;
import eu.solven.cleanthat.code_provider.github.event.pojo.WebhookRelevancyResult;
import eu.solven.cleanthat.lambda.AWebhooksLambdaFunction;
import eu.solven.cleanthat.lambda.dynamodb.SaveToDynamoDb;
import eu.solven.cleanthat.lambda.step0_checkwebhook.IWebhookEvent;

/**
 * Used to check if given webhook is associated to a valid configuration (e.g. to filter irrelevant repositories).
 * 
 * @author Benoit Lacelle
 *
 */
public class CheckConfigWebhooksLambdaFunction extends AWebhooksLambdaFunction {
	private static final Logger LOGGER = LoggerFactory.getLogger(CheckConfigWebhooksLambdaFunction.class);

	public AmazonDynamoDB makeDynamoDbClient() {
		return SaveToDynamoDb.makeDynamoDbClient();
	}

	@Override
	protected Map<String, ?> unsafeProcessOneEvent(IWebhookEvent input) {
		IGithubWebhookHandler makeWithFreshJwt = extracted(getAppContext());

		ICodeCleanerFactory cleanerFactory = getAppContext().getBean(ICodeCleanerFactory.class);

		WebhookRelevancyResult processAnswer =
				makeWithFreshJwt.filterWebhookEventTargetRelevantBranch(cleanerFactory, input);

		if (processAnswer.optHeadToClean().isPresent()) {
			AmazonDynamoDB client = makeDynamoDbClient();

			Map<String, Object> acceptedEvent = new LinkedHashMap<>(input.getBody());

			GitRepoBranchSha1 headToClean = processAnswer.optHeadToClean().get();

			ObjectMapper objectMapper = getAppContext().getBean(ObjectMapper.class);
			acceptedEvent.put("refToClean", objectMapper.convertValue(headToClean, Map.class));

			SaveToDynamoDb.saveToDynamoDb("cleanthat_accepted_events",
					new CleanThatWebhookEvent(input.getHeaders(), acceptedEvent),
					client);
		} else {
			LOGGER.info("Rejected due to: {}", processAnswer.optRejectedReason().get());
		}

		return Map.of("whatever", "done");
	}

	public static IGithubWebhookHandler extracted(ApplicationContext appContext) {
		IGithubWebhookHandlerFactory githubFactory = appContext.getBean(IGithubWebhookHandlerFactory.class);

		// TODO Cache the Github instance for the JWT duration
		IGithubWebhookHandler makeWithFreshJwt;
		try {
			makeWithFreshJwt = githubFactory.makeWithFreshJwt();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		return makeWithFreshJwt;
	}

}
