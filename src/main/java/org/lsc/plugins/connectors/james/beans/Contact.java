package org.lsc.plugins.connectors.james.beans;

import java.util.Optional;

import javax.xml.bind.annotation.XmlRootElement;

@XmlRootElement
public class Contact {
    private final String email;
    private final Optional<String> firstname;
    private final Optional<String> surname;

    public static String extractDomainFromEmail(String email) {
        return email.substring(email.indexOf("@") + 1);
    }

    public static String extractUsernameFromEmail(String email) {
        return email.substring(0, email.indexOf("@"));
    }

    public Contact(String email, Optional<String> firstname, Optional<String> surname) {
        this.email = email;
        this.firstname = firstname;
        this.surname = surname;
    }

    public Optional<String> getFirstname() {
        return firstname;
    }

    public Optional<String> getSurname() {
        return surname;
    }

    public String getEmail() {
        return email;
    }

    public String getDomain() {
        return extractDomainFromEmail(email);
    }

    public String getUsernameFromEmail(){
        return extractUsernameFromEmail(email);
    }

    public ContactNames getContactNames() {
        return new ContactNames(firstname, surname);
    }

}
