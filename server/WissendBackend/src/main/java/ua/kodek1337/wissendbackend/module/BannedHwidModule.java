package ua.kodek1337.wissendbackend.module;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Data
@Table(schema = "wissend", name = "hwid_banned")
public class BannedHwidModule {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String hwid;
}
