package org.lsc.plugins.connectors.james.beans;

import java.util.Objects;

import org.codehaus.jackson.annotate.JsonIgnoreProperties;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public class Forward {
	private String mailAddress;

	public Forward() {
	}

	@JsonCreator
	public Forward(@JsonProperty("mailAddress") String mailAddress) {
		this.mailAddress = mailAddress;
	}

	public String getMailAddress() {
		return mailAddress;
	}

	@Override
	public final boolean equals(Object o) {
		if (o instanceof Forward) {
			Forward forward = (Forward) o;

			return Objects.equals(this.mailAddress, forward.mailAddress);
		}
		return false;
	}

	@Override
	public final int hashCode() {
		return Objects.hash(mailAddress);
	}
}
