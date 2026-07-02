package ua.kodek1337.wissendbackend.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import ua.kodek1337.wissendbackend.module.*;
import ua.kodek1337.wissendbackend.repo.*;
import ua.kodek1337.wissendbackend.utils.JwtTokenProvider;
import ua.kodek1337.wissendbackend.utils.KeyGener;

import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;


@Service
public class UserService {

    @Autowired
    private UserRepo userRepo;

    @Autowired
    private UserNewRepo userNewRepo;

    @Autowired
    private SubRepo subRepo;



    @Autowired
    private KeysRepo keysRepo;

    private BCryptPasswordEncoder bCryptPasswordEncoder = new BCryptPasswordEncoder();

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    public ResponseEntity<?> register(String username, String password, String email) {
        if (username == null || username.trim().isEmpty()) {
            return ResponseEntity.status(400).body(Map.of("Error", "Username cannot be empty!"));
        }
        if (username.length() > 24) {
            return ResponseEntity.status(400).body(Map.of("Error", "Username cannot exceed 24 characters!"));
        }

        if (email == null || email.trim().isEmpty()) {
            return ResponseEntity.status(400).body(Map.of("Error", "Email cannot be empty!"));
        }
        if (!email.contains("@")) {
            return ResponseEntity.status(400).body(Map.of("Error", "Email must contain @ symbol!"));
        }
        String[] emailParts = email.split("@");
        if (emailParts.length != 2 || emailParts[1].isEmpty() || !emailParts[1].contains(".")) {
            return ResponseEntity.status(400).body(Map.of("Error", "Email format is invalid!"));
        }
        if (password == null || password.trim().isEmpty()) {
            return ResponseEntity.status(400).body(Map.of("Error", "Password cannot be empty!"));
        }

        if (userRepo.findByUsername(username).isPresent()) {
            return ResponseEntity.status(403).body(Map.of("Error", "Username is already taken!"));
        }

        if (userRepo.findByEmail(email).isPresent()) {
            return ResponseEntity.status(403).body(Map.of("Error", "Email is already in use!"));
        }

        String encodedPassword = bCryptPasswordEncoder.encode(password);
        UserModule userModule = new UserModule();

        userModule.setUsername(username);
        userModule.setPassword(encodedPassword);
        userModule.setEmail(email);
        userModule.setRole("GUEST");
        userModule.setHwid(null);
        userModule.setRegistrationDate(LocalDate.now());
        userModule.setLastLogin(null);

        userRepo.save(userModule);

        String token = jwtTokenProvider.generateToken(userModule);

        return ResponseEntity.status(200).body(Map.of(
                "message", "Регистрация успешна",
                "token", token,
                "username", userModule.getUsername(),
                "email", userModule.getEmail()
        ));
    }

    public ResponseEntity<?> login(String username, String password) {

        if (!userRepo.findByUsername(username).isPresent()) {
            return ResponseEntity.status(400).body(Map.of("Error", "Username not found!"));
        }

        if (!bCryptPasswordEncoder.matches(password, userRepo.findByUsername(username).get().getPassword())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Invalid credentials"));
        }

        String token = jwtTokenProvider.generateToken(userRepo.findByUsername(username).get());

        userRepo.findByUsername(username).get().setLastLogin(LocalDate.now());
        userRepo.save(userRepo.findByUsername(username).get());
        return ResponseEntity.status(200).body(Map.of("Success",  token));
    }

    public ResponseEntity<?> getMyProfyle(String name) {

        if (userNewRepo.findByUsername(name).getHwid() != null) {
            return ResponseEntity.status(200).body(Map.of("Email", userNewRepo.findByUsername(name).getEmail(), "Username", userNewRepo.findByUsername(name).getUsername(), "regdate", userNewRepo.findByUsername(name).getRegistrationDate(), "role", userNewRepo.findByUsername(name).getRole(), "hwid", userNewRepo.findByUsername(name).getHwid(), "id", userNewRepo.findByUsername(name).getId()));
        } else {
            return ResponseEntity.status(200).body(Map.of("Email", userNewRepo.findByUsername(name).getEmail(), "Username", userNewRepo.findByUsername(name).getUsername(), "regdate", userNewRepo.findByUsername(name).getRegistrationDate(), "role", userNewRepo.findByUsername(name).getRole(), "hwid", "Неизвестно", "id", userNewRepo.findByUsername(name).getId()));

        }
    }


    public ResponseEntity<?> activeKey(String name, String value) {
        UserModule userModule = userNewRepo.findByUsername(name);
        SubModule subModule = new SubModule();
        KeysModule keysModule = keysRepo.findByValue(value);

        if (keysModule.getUsed() > 0) {
            return ResponseEntity.status(403).body(Map.of("Error", "Key is already used!"));
        }

        int days = keysModule.getDays();
        if (!subRepo.findByUserId(userRepo.findByUsername(name).get().getId()).isPresent()) {
            if (!userModule.getRole().equals("admin")) {
                userModule.setRole("USER");
            }
            subModule.setUserId(userModule.getId().intValue());
            subModule.setEntdatesub(LocalDate.now());
            subModule.setOutdatesub(LocalDate.now().plusDays(days));
            keysModule.setUsed(userModule.getId().intValue());
            subRepo.save(subModule);
            keysRepo.save(keysModule);
        } else {
            SubModule subModule1 = subRepo.findByUserId(userRepo.findByUsername(name).get().getId()).get();
            subModule1.setEntdatesub(LocalDate.now());
            subModule1.setOutdatesub(LocalDate.now().plusDays(days));
            subRepo.save(subModule1);
        }

        return ResponseEntity.status(200).body(Map.of("Success",  "Key activated!"));
    }

    public ResponseEntity<?> createKeys(int days) {
        String value = KeyGener.generateRandomString();
        KeysModule keysModule = new KeysModule();
        keysModule.setDays(days);
        keysModule.setUsed(0);
        keysModule.setValue(value);
        keysRepo.save(keysModule);

        return ResponseEntity.status(200).body(Map.of("Success",  value));
    }

    public ResponseEntity<?> resetPassword(String username, String password) {
        UserModule user = userNewRepo.findByUsername(username);
        user.setPassword(bCryptPasswordEncoder.encode(password));
        userRepo.save(user);

        return ResponseEntity.status(200).body(Map.of("Success",  "Your password: " + password));
    }

    public ResponseEntity<?> createSub(int id, int days) {
        UserModule user = userNewRepo.findById(id).get();
        if (subRepo.findByUserId(user.getId()).isEmpty()) {
            SubModule subModule = new SubModule();
            subModule.setUserId(user.getId().intValue());
            subModule.setEntdatesub(LocalDate.now());
            subModule.setOutdatesub(LocalDate.now().plusDays(days));
            subRepo.save(subModule);
        } else {
            SubModule subModule = subRepo.findByUserId(user.getId()).get();
            subModule.setUserId(user.getId().intValue());
            subModule.setEntdatesub(LocalDate.now());
            subModule.setOutdatesub(LocalDate.now().plusDays(days));
            subRepo.save(subModule);
        }

        return ResponseEntity.status(200).body(Map.of("Success", "To the user: " + user.getUsername() + " a subscription was issued"));
    }

    public ResponseEntity<?> deleteSub(long id) {
        SubModule subModule = subRepo.findById(id).get();
        subRepo.delete(subModule);
        subRepo.save(subModule);

        return ResponseEntity.status(200).body(Map.of("Success",  "Your subscription was removed"));
    }

    public ResponseEntity<?> deleteKeys(String keys) {
        KeysModule keysModule = keysRepo.findByValue(keys);
        keysRepo.delete(keysModule);
        keysRepo.save(keysModule);

        return ResponseEntity.status(200).body(Map.of("Success",  "keys is deleted"));
    }

    public ResponseEntity<?> getUserList() {
        return ResponseEntity.status(200).body(userRepo.findAll());
    }

    public ResponseEntity<?> getUserSubList() {
        return ResponseEntity.status(200).body(subRepo.findAll());
    }

    public ResponseEntity<?> getKeysList() {
        return ResponseEntity.status(200).body(keysRepo.findAll());
    }

    public ResponseEntity<?> getSub(String name) {
        var user = userRepo.findByUsername(name)
                .orElse(null);
        if (user == null) {
            return ResponseEntity.status(403).body(Map.of("Error", "user not found!"));
        }
        var subModuleOpt = subRepo.findByUserId(user.getId());
        if (subModuleOpt.isEmpty()) {
            return ResponseEntity.status(403).body(Map.of("Error", "sub not found!"));
        }
        var sub = subModuleOpt.get();
        if (sub.getOutdatesub().isAfter(LocalDate.now())) {
            return ResponseEntity.ok(sub);
        }
        return ResponseEntity.status(403).body(Map.of("Error", "sub expired"));
    }

    public ResponseEntity<?> repass(String name, String oldPass, String newPass) {
        return null;
    }

    public ResponseEntity<?> bannedUser(int id) {
        UserModule user = userNewRepo.findById(id).get();
        user.setBanned(1);
        userNewRepo.save(user);

        return ResponseEntity.status(200).body(Map.of("Success",  "Your banned user: " + user.getUsername()));
    }

    @Autowired
    private PromoRepo promoRepo;

    public ResponseEntity<?> createPromocode(String promo, int discount, int days, int activationOut) {
        PromoModules promoModules = new PromoModules();
        promoModules.setValue(promo);
        promoModules.setDiscount(discount);
        promoModules.setActivations(0);
        promoModules.setActivations_out(activationOut);
        promoModules.setOut_date(LocalDate.now().plusDays(days));
        promoRepo.save(promoModules);

        return ResponseEntity.status(200).body(Map.of("Success",  "Your promo has been created: " + promo));
    }

    public ResponseEntity<?> getPromoList() {
        return ResponseEntity.status(200).body(promoRepo.findAll());
    }

    @Autowired
    private HwidBannedRepo hwidBannedRepo;

    public ResponseEntity<?> bannedTriger(String name, String hwid) {
        UserModule user = userRepo.findByUsername(name).get();
        user.setBanned(1);
        BannedHwidModule bannedHwidModule = new BannedHwidModule();
        bannedHwidModule.setHwid(hwid);
        hwidBannedRepo.save(bannedHwidModule);
        userRepo.save(user);

        if (subRepo.findByUserId(user.getId()).isPresent()) {
            Optional<SubModule> subs = subRepo.findByUserId(user.getId());
            subRepo.delete(subs.get());
        }

        return ResponseEntity.status(200).body(Map.of("Success",  "banned"));
    }
}