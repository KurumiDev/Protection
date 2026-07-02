package ua.kodek1337.wissendbackend.controller.admin;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import ua.kodek1337.wissendbackend.repo.UserNewRepo;
import ua.kodek1337.wissendbackend.repo.UserRepo;
import ua.kodek1337.wissendbackend.service.UserService;

import java.util.Map;

@Controller
@RequestMapping("admin/read")
@CrossOrigin(origins = "*")
public class ReadController {

    @Autowired
    private UserNewRepo userNewRepo;

    @Autowired
    private UserService userService;

    @Autowired
    private UserRepo userRepo;

    @PostMapping("banned")
    public ResponseEntity<?> banned(Authentication authentication, @RequestParam int id) {
        if (userRepo.findByUsername(authentication.getName()).get().getBanned() == 1) {
            return ResponseEntity.status(403).body(Map.of("Error", "Username banned"));
        }
        if (authentication == null || authentication.getPrincipal() == null || authentication.getPrincipal().equals("anonymousUser") || authentication.getName() == null || id == 0) {
            return ResponseEntity.status(200).body("Invalid response");
        }
        if (userNewRepo.findByUsername(authentication.getName()).getRole().equals("admin")) {
            return userService.bannedUser(id);
        } else {
            return ResponseEntity.status(403).body(Map.of("Error", "Не достаточно прав"));
        }
    }

    @PostMapping("resetPassword")
    private ResponseEntity<?> resetUserPasword(Authentication authentication, @RequestParam String username, @RequestParam String password) {
        if (userRepo.findByUsername(authentication.getName()).get().getBanned() == 1) {
            return ResponseEntity.status(403).body(Map.of("Error", "Username banned"));
        }
        if (authentication == null || authentication.getPrincipal() == null || authentication.getPrincipal().equals("anonymousUser") || authentication.getName() == null || username == null || password == null) {
            return ResponseEntity.status(200).body("Invalid response");
        }
        if (userNewRepo.findByUsername(authentication.getName()).getRole().equals("admin")) {
            return userService.resetPassword(username, password);
        } else {
            return ResponseEntity.status(403).body(Map.of("Error", "Не достаточно прав"));
        }
    }

    @PostMapping("deleteSub")
    private ResponseEntity<?> deleteSub(Authentication authentication, @RequestParam long id) {
        if (userRepo.findByUsername(authentication.getName()).get().getBanned() == 1) {
            return ResponseEntity.status(403).body(Map.of("Error", "Username banned"));
        }
        if (authentication == null || authentication.getPrincipal() == null || authentication.getPrincipal().equals("anonymousUser") || authentication.getName() == null || id == 0) {
            return ResponseEntity.status(200).body("Invalid response");
        }
        if (userNewRepo.findByUsername(authentication.getName()).getRole().equals("admin")) {
            return userService.deleteSub(id);
        } else {
            return ResponseEntity.status(403).body(Map.of("Error", "Не достаточно прав"));
        }
    }



    @PostMapping("deleteKeys")
    private ResponseEntity<?> deleteKeys(Authentication authentication, @RequestParam String keys) {
        if (userRepo.findByUsername(authentication.getName()).get().getBanned() == 1) {
            return ResponseEntity.status(403).body(Map.of("Error", "Username banned"));
        }
        if (authentication == null || authentication.getPrincipal() == null || authentication.getPrincipal().equals("anonymousUser") || authentication.getName() == null || keys == null) {
            return ResponseEntity.status(200).body("Invalid response");
        }
        if (userNewRepo.findByUsername(authentication.getName()).getRole().equals("admin")) {
            return userService.deleteKeys(keys);
        } else {
            return ResponseEntity.status(403).body(Map.of("Error", "Не достаточно прав"));
        }
    }
}
