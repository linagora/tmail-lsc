package org.lsc.plugins.connectors.james.beans;

import java.util.Objects;
import java.util.Optional;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public class Contact {
    private final String emailAddress;
    private final Optional<String> firstname;
    private final Optional<String> surname;

    public static String extractDomainFromEmail(String email) {
        return email.substring(email.indexOf("@") + 1);
    }

    public static String extractUsernameFromEmail(String email) {
        return email.substring(0, email.indexOf("@"));
    }

    @JsonCreator
    public Contact(@JsonProperty("emailAddress") String email,
                   @JsonProperty("firstname") Optional<String> firstname,
                   @JsonProperty("surname") Optional<String> surname) {
        this.emailAddress = email;
        this.firstname = firstname;
        this.surname = surname;
    }

    public Optional<String> getFirstname() {
        return firstname;
    }

    public Optional<String> getSurname() {
        return surname;
    }

    public String getEmailAddress() {
        return emailAddress;
    }

    @JsonIgnore
    public String getDomain() {
        return extractDomainFromEmail(emailAddress);
    }

    @JsonIgnore
    public String getUsernameFromEmail(){
        return extractUsernameFromEmail(emailAddress);
    }

    @JsonIgnore
    public ContactNames getContactNames() {
        return new ContactNames(firstname, surname);
    }

    @Override
    public final boolean equals(Object o) {
        if (o instanceof Contact) {
            Contact that = (Contact) o;

            return Objects.equals(this.emailAddress, that.emailAddress)
                && Objects.equals(this.firstname, that.firstname)
                && Objects.equals(this.surname, that.surname);
        }
        return false;
    }

    @Override
    public final int hashCode() {
        return Objects.hash(emailAddress, firstname, surname);
    }
}
