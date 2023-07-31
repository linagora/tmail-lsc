package org.lsc.plugins.connectors.james.beans;

import java.util.Objects;

import org.codehaus.jackson.annotate.JsonIgnoreProperties;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public class AddressMappingDto {
	private String type;
	private String mapping;

	public AddressMappingDto() {
	}

	@JsonCreator
	public AddressMappingDto(@JsonProperty("type") String type,
							 @JsonProperty("mapping") String mapping) {
		this.type = type;
		this.mapping = mapping;
	}

	public String getMapping() {
		return mapping;
	}

	public String getType() {
		return type;
	}

	@Override
	public final boolean equals(Object o) {
		if (o instanceof AddressMappingDto) {
			AddressMappingDto addressMappingDto = (AddressMappingDto) o;

			return Objects.equals(this.type, addressMappingDto.type) &&
				Objects.equals(this.mapping, addressMappingDto.mapping);
		}
		return false;
	}

	@Override
	public final int hashCode() {
		return Objects.hash(type, mapping);
	}
}
