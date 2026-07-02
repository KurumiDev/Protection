package ua.kodek1337.wissendbackend.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import ua.kodek1337.wissendbackend.module.BannedHwidModule;

import java.util.Optional;

public interface HwidBannedRepo extends JpaRepository<BannedHwidModule,Long> {
    Optional<BannedHwidModule> findByHwid(String hwid);
}
