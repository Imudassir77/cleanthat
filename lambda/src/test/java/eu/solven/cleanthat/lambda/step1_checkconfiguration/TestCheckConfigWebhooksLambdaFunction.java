package eu.solven.cleanthat.lambda.step1_checkconfiguration;

import java.io.IOException;
import java.util.Optional;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.junit4.SpringRunner;

import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.model.PutItemRequest;
import com.amazonaws.services.dynamodbv2.model.PutItemResult;
import com.fasterxml.jackson.databind.ObjectMapper;

import eu.solven.cleanthat.code_provider.github.event.ICodeCleanerFactory;
import eu.solven.cleanthat.code_provider.github.event.IGithubWebhookHandler;
import eu.solven.cleanthat.code_provider.github.event.IGithubWebhookHandlerFactory;
import eu.solven.cleanthat.code_provider.github.event.pojo.GitRepoBranchSha1;
import eu.solven.cleanthat.code_provider.github.event.pojo.HeadAndOptionalBase;
import eu.solven.cleanthat.code_provider.github.event.pojo.WebhookRelevancyResult;
import eu.solven.cleanthat.lambda.step0_checkwebhook.IWebhookEvent;

@RunWith(SpringRunner.class)
@MockBean({ IGithubWebhookHandlerFactory.class, ICodeCleanerFactory.class, ObjectMapper.class })
public class TestCheckConfigWebhooksLambdaFunction {

	final IGithubWebhookHandler webhookHandler = Mockito.mock(IGithubWebhookHandler.class);
	final AmazonDynamoDB dynamoDb = Mockito.mock(AmazonDynamoDB.class);

	@Autowired
	ApplicationContext appContext;

	@Before
	public void prepareMocks() throws BeansException, IOException {
		Mockito.when(appContext.getBean(IGithubWebhookHandlerFactory.class).makeWithFreshJwt())
				.thenReturn(webhookHandler);

		Mockito.when(dynamoDb.putItem(Mockito.any(PutItemRequest.class))).thenReturn(new PutItemResult());
	}

	@Test
	public void testPersistInDynamoDb() {
		CheckConfigWebhooksLambdaFunction function = new CheckConfigWebhooksLambdaFunction() {
			@Override
			public AmazonDynamoDB makeDynamoDbClient() {
				return dynamoDb;
			}
		};
		function.setApplicationContext(appContext);

		IWebhookEvent input = Mockito.mock(IWebhookEvent.class);

		GitRepoBranchSha1 head = new GitRepoBranchSha1("someRepoName", "someRef", "someSha1");
		Mockito.when(webhookHandler
				.filterWebhookEventTargetRelevantBranch(appContext.getBean(ICodeCleanerFactory.class), input))
				.thenReturn(WebhookRelevancyResult.relevant(new HeadAndOptionalBase(head, Optional.empty())));

		function.unsafeProcessOneEvent(input);

		Mockito.verify(dynamoDb).putItem(Mockito.any(PutItemRequest.class));
	}
}
