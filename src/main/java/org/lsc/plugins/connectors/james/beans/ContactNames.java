package org.lsc.plugins.connectors.james.beans;

import java.util.Optional;

import javax.xml.bind.annotation.XmlRootElement;

@XmlRootElement
public class ContactNames {
    private final Optional<String> firstname;
    private final Optional<String> surname;

    public ContactNames(Optional<String> firstname, Optional<String> surname) {
        this.firstname = firstname;
        this.surname = surname;
    }

    public Optional<String> getFirstname() {
        return firstname;
    }

    public Optional<String> getSurname() {
        return surname;
    }
}
