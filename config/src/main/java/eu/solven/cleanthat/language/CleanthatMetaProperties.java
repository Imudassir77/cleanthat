package eu.solven.cleanthat.language;

import java.util.Arrays;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategy;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import eu.solven.cleanthat.github.CleanthatRefFilterProperties;

/**
 * The configuration of what is not related to a language.
 *
 * @author Benoit Lacelle
 */
@JsonIgnoreProperties({ "commit_pull_requests", "commit_main_branch" })
@JsonNaming(PropertyNamingStrategy.SnakeCaseStrategy.class)
public class CleanthatMetaProperties {

	// The labels to apply to created PRs
	private List<String> labels = Arrays.asList();

	private CleanthatRefFilterProperties refs;

	public List<String> getLabels() {
		return labels;
	}

	public void setLabels(List<String> labels) {
		this.labels = List.copyOf(labels);
	}

	public CleanthatRefFilterProperties getRefs() {
		return refs;
	}

	public void setRefs(CleanthatRefFilterProperties refs) {
		this.refs = refs;
	}

}
