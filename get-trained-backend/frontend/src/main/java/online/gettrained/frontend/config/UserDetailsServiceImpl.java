package online.gettrained.frontend.config;

import online.gettrained.backend.domain.user.CurrentUser;
import online.gettrained.backend.domain.user.User;
import online.gettrained.backend.services.user.UserService;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class UserDetailsServiceImpl implements UserDetailsService {

  private final UserService userService;

  public UserDetailsServiceImpl(UserService userService) {
    this.userService = userService;
  }

  @Override
  public UserDetails loadUserByUsername(String userNameOrEmail) throws UsernameNotFoundException {
    User user = null;

    if (userNameOrEmail.contains("@")) {
      user = userService.findOneByEmailWithRoles(userNameOrEmail)
          .orElseThrow(
              () -> new UsernameNotFoundException(
                  String.format("User with email=%s was not found",
                      userNameOrEmail)));
    } else {
      user = userService.findOneByUserNameWithRoles(userNameOrEmail)
          .orElseThrow(() -> new UsernameNotFoundException(
              String.format("User with userName=%s was not found",
                  userNameOrEmail)));
    }

    return new CurrentUser(user);
  }
}
