package ua.kodek1337.wissendbackend.controller.admin;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import ua.kodek1337.wissendbackend.repo.UserNewRepo;
import ua.kodek1337.wissendbackend.service.UserService;

import java.util.Map;

@Controller
@RequestMapping("admin/get")
@CrossOrigin(origins = "*")
public class GetController {

    @Autowired
    private UserNewRepo userNewRepo;

    @Autowired
    private UserService userService;

    @PostMapping("getUserList")
    public ResponseEntity<?> getUserList(Authentication authentication) {
        if (authentication == null || authentication.getPrincipal() == null || authentication.getPrincipal().equals("anonymousUser") || authentication.getName() == null) {
            return ResponseEntity.status(200).body("Invalid response");
        }
        if (userNewRepo.findByUsername(authentication.getName()).getRole().equals("admin")) {
            return userService.getUserList();
        } else {
            return ResponseEntity.status(403).body(Map.of("Error", "Не достаточно прав"));
        }
    }

    @PostMapping("getUserSubList")
    public ResponseEntity<?> getUserSubList(Authentication authentication) {
        if (authentication == null || authentication.getPrincipal() == null || authentication.getPrincipal().equals("anonymousUser") || authentication.getName() == null) {
            return ResponseEntity.status(200).body("Invalid response");
        }
        if (userNewRepo.findByUsername(authentication.getName()).getRole().equals("admin")) {
            return userService.getUserSubList();
        } else {
            return ResponseEntity.status(403).body(Map.of("Error", "Не достаточно прав"));
        }
    }

    @PostMapping("getKeysList")
    public ResponseEntity<?> getKeysList(Authentication authentication) {
        if (authentication == null || authentication.getPrincipal() == null || authentication.getPrincipal().equals("anonymousUser") || authentication.getName() == null) {
            return ResponseEntity.status(200).body("Invalid response");
        }
        if (userNewRepo.findByUsername(authentication.getName()).getRole().equals("admin")) {
            return userService.getKeysList();
        } else {
            return ResponseEntity.status(403).body(Map.of("Error", "Не достаточно прав"));
        }
    }
}
