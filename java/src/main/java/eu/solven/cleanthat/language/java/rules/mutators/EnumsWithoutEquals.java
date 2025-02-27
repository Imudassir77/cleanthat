package eu.solven.cleanthat.language.java.rules.mutators;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.github.javaparser.resolution.types.ResolvedReferenceType;

import cormoran.pepper.logging.PepperLogHelper;
import eu.solven.cleanthat.language.java.IJdkVersionConstants;
import eu.solven.cleanthat.language.java.rules.AJavaParserRule;
import eu.solven.cleanthat.language.java.rules.meta.IClassTransformer;

/**
 * Prevent relying .equals on {@link Enum} types
 *
 * @author Benoit Lacelle
 */
// see https://jsparrow.github.io/rules/enums-without-equals.html#properties
// https://stackoverflow.com/questions/1750435/comparing-java-enum-members-or-equals
public class EnumsWithoutEquals extends AJavaParserRule implements IClassTransformer {

	private static final Logger LOGGER = LoggerFactory.getLogger(EnumsWithoutEquals.class);

	@Override
	public String minimalJavaVersion() {
		return IJdkVersionConstants.JDK_5;
	}

	@Override
	public String getId() {
		return "EnumsWithoutEquals";
	}

	@Override
	public String jsparrowUrl() {
		return "https://jsparrow.github.io/rules/enums-without-equals.html";
	}

	// https://stackoverflow.com/questions/55309460/how-to-replace-expression-by-string-in-javaparser-ast
	@SuppressWarnings("PMD.CognitiveComplexity")
	@Override
	protected boolean processNotRecursively(Node node) {
		LOGGER.debug("{}", PepperLogHelper.getObjectAndClass(node));

		AtomicBoolean mutated = new AtomicBoolean(false);
		onMethodName(node, "equals", (methodCall, scope, type) -> {
			if (type.isReferenceType()) {
				boolean isEnum = false;
				ResolvedReferenceType referenceType = type.asReferenceType();

				referenceType.isJavaLangEnum();

				String className = referenceType.getQualifiedName();

				try {
					Class<?> clazz = Class.forName(className, false, Thread.currentThread().getContextClassLoader());

					isEnum = Enum.class.isAssignableFrom(clazz);
				} catch (ClassNotFoundException e) {
					LOGGER.debug("Class is not available", e);
				}

				if (isEnum && methodCall.getArguments().size() == 1) {
					Expression singleArgument = methodCall.getArgument(0);

					Optional<Node> optParentNode = methodCall.getParentNode();

					boolean isNegated;
					if (optParentNode.isPresent()) {
						Node parent = optParentNode.get();

						if (parent instanceof UnaryExpr
								&& ((UnaryExpr) parent).getOperator() == UnaryExpr.Operator.LOGICAL_COMPLEMENT) {
							isNegated = true;
						} else {
							isNegated = false;
						}
					} else {
						isNegated = false;
					}

					if (isNegated) {
						BinaryExpr replacement = new BinaryExpr(scope, singleArgument, BinaryExpr.Operator.NOT_EQUALS);

						if (tryReplace(optParentNode.get(), replacement)) {
							mutated.set(true);
						}
					} else {
						BinaryExpr replacement = new BinaryExpr(scope, singleArgument, BinaryExpr.Operator.EQUALS);

						if (tryReplace(node, replacement)) {
							mutated.set(true);
						}
					}
				}
			}
		});

		return mutated.get();
	}

}
