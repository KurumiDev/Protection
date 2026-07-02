package ua.kodek1337.wissendbackend.module;

import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity
@Table(schema = "wissend", name = "keys")
public class KeysModule {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String value;
    private int days;
    private int used;
}
