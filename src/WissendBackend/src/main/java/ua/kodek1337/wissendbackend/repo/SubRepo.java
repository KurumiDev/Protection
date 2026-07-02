package ua.kodek1337.wissendbackend.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import ua.kodek1337.wissendbackend.module.SubModule;

import java.util.Optional;

public interface SubRepo extends JpaRepository<SubModule,Long> {
    boolean findByUserId(int userId);

    Optional<SubModule> findByUserId(long userId);
}
