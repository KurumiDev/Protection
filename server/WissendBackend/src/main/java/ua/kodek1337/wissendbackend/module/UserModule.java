package ua.kodek1337.wissendbackend.module;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDate;

@Data
@Entity
@Table(schema = "wissend", name = "users")
public class UserModule {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String username;
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String password;
    private String email;
    private String role;
    @Column(name = "last_login")
    private LocalDate lastLogin;
    @Column(name = "reg_date")
    private LocalDate registrationDate;
    private String hwid;
    @Column(name = "banned")
    private int banned;
}
