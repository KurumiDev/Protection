package ua.kodek1337.wissendbackend.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import ua.kodek1337.wissendbackend.module.KeysModule;

public interface KeysRepo extends JpaRepository<KeysModule,Long> {

    KeysModule findByValue(String value);

}
