package org.lsc.plugins.connectors.james.beans;

import java.util.Objects;
import java.util.Optional;

import org.codehaus.jackson.annotate.JsonIgnoreProperties;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public class Identity {
	public static final int DEFAULT_IDENTITY_SORT_ORDER = 0;

	public static String toDisplayName(Optional<String> firstname, Optional<String> surname, String fallbackEmailAddress) {
		if (firstname.isPresent() && surname.isPresent()) {
			return firstname.get() + " " + surname.get();
		}
		if (firstname.isPresent()) {
			return firstname.get();
		}
		if (surname.isPresent()) {
			return surname.get();
		}
		return fallbackEmailAddress;
	}

	private String email;
	private String name;
	private int sortOrder;

	public Identity() {
	}

	@JsonCreator
	public Identity(@JsonProperty("email") String email,
					@JsonProperty("name") String name,
					@JsonProperty("sortOrder") Integer sortOrder) {
		this.email = email;
		this.name = name;
		this.sortOrder = sortOrder;
	}

	public String getName() {
		return name;
	}

	public String getEmail() {
		return email;
	}

	public int getSortOrder() {
		return sortOrder;
	}

	@Override
	public final boolean equals(Object o) {
		if (o instanceof Identity) {
			Identity identity = (Identity) o;

			return Objects.equals(this.name, identity.name)
				&& Objects.equals(this.email, identity.email)
				&& Objects.equals(this.sortOrder, identity.sortOrder);
		}
		return false;
	}

	@Override
	public final int hashCode() {
		return Objects.hash(name, email, sortOrder);
	}
}
