package ua.kodek1337.wissendbackend.module;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

@Data
@Entity
@Table(schema = "wissend", name = "version")
public class VersionModule {
    @Id
    private Long id;

    @Column(name = "client_version")
    private int version;
}
