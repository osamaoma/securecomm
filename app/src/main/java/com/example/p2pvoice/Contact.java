package com.example.p2pvoice;

/** A saved contact = a username plus an optional display name. */
public class Contact {
    public String username;     // routing id (lowercase)
    public String displayName;  // shown in UI

    public Contact(String username, String displayName) {
        this.username = username;
        this.displayName = (displayName == null || displayName.trim().isEmpty())
                ? username : displayName;
    }
}
