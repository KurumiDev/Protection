package ua.kodek1337.wissendbackend.controller.guard;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import ua.kodek1337.wissendbackend.module.SubModule;
import ua.kodek1337.wissendbackend.module.UserModule;
import ua.kodek1337.wissendbackend.repo.SubRepo;
import ua.kodek1337.wissendbackend.repo.UserRepo;

import java.time.LocalDate;

@Controller
@RequestMapping("guard")
@CrossOrigin(origins = "*")
public class GuardController {

    @Autowired
    private SubRepo subRepo;

    @Autowired
    private UserRepo userRepo;


    public static String caesar(String text, int shift) {
        StringBuilder result = new StringBuilder();

        for (char c : text.toCharArray()) {
            if (c >= 'a' && c <= 'z') {
                result.append((char) ((c - 'a' + shift + 26) % 26 + 'a'));
            } else if (c >= 'A' && c <= 'Z') {
                result.append((char) ((c - 'A' + shift + 26) % 26 + 'A'));
            } else {
                result.append(c);
            }
        }
        return result.toString();
    }

    private static String noise(int length) {
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < length; i++) {
            sb.append(chars.charAt((int) (Math.random() * chars.length())));
        }
        return sb.toString();
    }


    @PostMapping("login")
    public ResponseEntity<?> login(@RequestParam String username,
                                   @RequestParam String password,
                                   @RequestParam String token,
                                   @RequestParam String hwid
    ) {
        if (username == null || password == null || token == null) return ResponseEntity.badRequest().build();

        UserModule user = userRepo.findByUsername(username).orElse(null);
        if (user == null) return ResponseEntity.badRequest().build();

        SubModule sub = subRepo.findByUserId(user.getId()).orElse(null);
        if (sub == null) return ResponseEntity.badRequest().build();

        if (!sub.getOutdatesub().isAfter(LocalDate.now())) {
            return ResponseEntity.status(403).body("sub expired");
        }

        if (user.getHwid() != null) {
            if (!user.getHwid().equals(hwid)) {
                return ResponseEntity.badRequest().build();
            }
        } else {
            user.setHwid(hwid);
            userRepo.save(user);
        }

        String success = "successnewnaasuka";

        String base = caesar(token, 15) + caesar(success, 15);

        String noise1 = noise(12);
        String noise2 = noise(18);
        String mixed = noise1 + base + noise2;

        String finalResponse = caesar(mixed, 15);

        return ResponseEntity.ok(finalResponse);
    }
}
