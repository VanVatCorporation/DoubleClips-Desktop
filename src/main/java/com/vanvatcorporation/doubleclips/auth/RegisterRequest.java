package com.vanvatcorporation.doubleclips.auth;

public class RegisterRequest {
    private String username;
    private String email;
    private String password;
    private String firstName;
    private String lastName;

    public RegisterRequest(String username, String email, String password, String firstName, String lastName) {
        this.username = username;
        this.email = email;
        this.password = password;
        this.firstName = firstName;
        this.lastName = lastName;
    }

    public String getUsername() { return username; }
    public String getEmail() { return email; }
    public String getPassword() { return password; }
    public String getFirstName() { return firstName; }
    public String getLastName() { return lastName; }
}
