package org.lsc.plugins.connectors.james.beans;

import java.util.Optional;

import org.codehaus.jackson.annotate.JsonIgnoreProperties;
import org.codehaus.jackson.annotate.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public class ContactDTO {
    @JsonProperty("id")
    private final String id;

    @JsonProperty("emailAddress")
    private final String emailAddress;

    @JsonProperty("firstname")
    private final Optional<String> firstname;

    @JsonProperty("surname")
    private final Optional<String> surname;

    public ContactDTO(String id, String emailAddress, Optional<String> firstname, Optional<String> surname) {
        this.id = id;
        this.emailAddress = emailAddress;
        this.firstname = firstname;
        this.surname = surname;
    }

    public String getId() {
        return id;
    }

    public String getEmailAddress() {
        return emailAddress;
    }

    public Optional<String> getFirstname() {
        return firstname;
    }

    public Optional<String> getSurname() {
        return surname;
    }

}
