package ua.kodek1337.wissendbackend.module;

import jakarta.persistence.*;
import lombok.Data;
import org.springframework.cglib.core.Local;

import java.time.LocalDate;

@Data
@Entity
@Table(schema = "wissend", name = "sub")
public class SubModule {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "userid")
    private int userId;
    private LocalDate entdatesub;
    private LocalDate outdatesub;
}
