package ua.kodek1337.wissendbackend.module;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDate;

@Data
@Entity
@Table(schema = "wissend", name = "promocode")
public class PromoModules {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String value;
    private int discount;
    @Column(name = "out_date")
    private LocalDate out_date;
    private int activations;
    private int activations_out;

}
