package eu.solven.cleanthat.language.java.rules.mutators;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.resolution.types.ResolvedType;

import cormoran.pepper.logging.PepperLogHelper;
import eu.solven.cleanthat.language.java.IJdkVersionConstants;
import eu.solven.cleanthat.language.java.rules.AJavaParserRule;
import eu.solven.cleanthat.language.java.rules.meta.IClassTransformer;

/**
 * cases inspired from #description
 *
 * @author Sébastien Collard
 */
public class CreateTempFilesUsingNio extends AJavaParserRule implements IClassTransformer {

	private static final Logger LOGGER = LoggerFactory.getLogger(UseIsEmptyOnCollections.class);

	@Override
	public String minimalJavaVersion() {
		// java.nio.Files has been introduced in JDK7
		return IJdkVersionConstants.JDK_7;
	}

	@Override
	public String sonarUrl() {
		return "https://rules.sonarsource.com/java/RSPEC-2976";
	}

	@Override
	public String jsparrowUrl() {
		return "https://jsparrow.github.io/rules/create-temp-files-using-java-nio.html";
	}

	@Override
	public String getId() {
		return "CreateTempFilesUsingNio";
	}

	@Override
	protected boolean processNotRecursively(Node node) {
		LOGGER.debug("{}", PepperLogHelper.getObjectAndClass(node));
		// ResolvedMethodDeclaration test;
		if (!(node instanceof MethodCallExpr)) {
			return false;
		}
		MethodCallExpr methodCallExpr = (MethodCallExpr) node;
		if (!"createTempFile".equals(methodCallExpr.getName().getIdentifier())) {
			return false;
		}
		Optional<Boolean> optIsStatic = mayStaticCall(methodCallExpr);
		if (optIsStatic.isPresent() && !optIsStatic.get()) {
			return false;
		}
		Optional<Expression> optScope = methodCallExpr.getScope();
		if (optScope.isPresent()) {
			Optional<ResolvedType> type = optResolvedType(optScope.get());
			if (type.isEmpty() || !"java.io.File".equals(type.get().asReferenceType().getQualifiedName())) {
				return false;
			}
			LOGGER.debug("Found : {}", node);
			if (process(methodCallExpr)) {
				return true;
			}
		}
		return false;
	}

	private Optional<Boolean> mayStaticCall(MethodCallExpr methodCallExpr) {
		try {
			return Optional.of(methodCallExpr.resolve().isStatic());
		} catch (Exception e) {
			// Knowing if class is static requires a SymbolResolver:
			// 'java.lang.IllegalStateException: Symbol resolution not configured: to configure consider setting a
			// SymbolResolver in the ParserConfiguration'
			LOGGER.debug("arg", e);
			return Optional.empty();
		}
	}

	private boolean process(MethodCallExpr methodExp) {
		List<Expression> arguments = methodExp.getArguments();
		Optional<MethodCallExpr> optToPath;
		NameExpr newStaticClass = new NameExpr("Files");
		String newStaticMethod = "createTempFile";
		int minArgSize = 2;
		if (arguments.size() == minArgSize) {
			// Create in default tmp directory
			LOGGER.debug("Add java.nio.file.Files to import");
			methodExp.tryAddImportToParentCompilationUnit(Files.class);
			optToPath = Optional.of(new MethodCallExpr(newStaticClass, newStaticMethod, methodExp.getArguments()));
		} else if (arguments.size() == minArgSize + 1) {
			Expression arg0 = methodExp.getArgument(0);
			Expression arg1 = methodExp.getArgument(1);
			Expression arg3 = methodExp.getArgument(2);
			if (arg3.isObjectCreationExpr()) {
				methodExp.tryAddImportToParentCompilationUnit(Paths.class);
				ObjectCreationExpr objectCreation = (ObjectCreationExpr) methodExp.getArgument(minArgSize);
				NodeList<Expression> objectCreationArguments = objectCreation.getArguments();
				NodeList<Expression> replaceArguments =
						new NodeList<>(new MethodCallExpr(new NameExpr("Paths"), "get", objectCreationArguments),
								arg0,
								arg1);
				optToPath = Optional.of(new MethodCallExpr(newStaticClass, newStaticMethod, replaceArguments));
			} else if (arg3.isNameExpr()) {
				NodeList<Expression> replaceArguments = new NodeList<>(new MethodCallExpr(arg3, "toPath"), arg0, arg1);
				optToPath = Optional.of(new MethodCallExpr(newStaticClass, newStaticMethod, replaceArguments));
			} else if (arg3.isNullLiteralExpr()) {
				// 'null' is managed specifically as Files.createTempFile does not accept a null as directory
				NodeList<Expression> replaceArguments = new NodeList<>(arg0, arg1);
				optToPath = Optional.of(new MethodCallExpr(newStaticClass, newStaticMethod, replaceArguments));
			} else {
				optToPath = Optional.empty();
			}
		} else {
			optToPath = Optional.empty();
		}
		optToPath.ifPresent(toPath -> {
			methodExp.tryAddImportToParentCompilationUnit(Files.class);
			LOGGER.info("Turning {} into {}", methodExp, toPath);
			methodExp.replace(new MethodCallExpr(toPath, "toFile"));
		});
		return optToPath.isPresent();
	}
}
