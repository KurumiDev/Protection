package ua.kodek1337.wissendbackend.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.CrudRepository;
import ua.kodek1337.wissendbackend.module.VersionModule;

public interface VersionRepo extends JpaRepository<VersionModule, Integer> {
}
