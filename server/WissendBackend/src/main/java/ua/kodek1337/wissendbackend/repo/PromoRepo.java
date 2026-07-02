package ua.kodek1337.wissendbackend.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import ua.kodek1337.wissendbackend.module.PromoModules;

public interface PromoRepo extends JpaRepository<PromoModules, Integer> {
}
