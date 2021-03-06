package online.gettrained.backend.domain.user;

import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.stream.Collectors;

/**
 * Current user
 */
public class CurrentUser extends org.springframework.security.core.userdetails.User {

  private static final long serialVersionUID = 1L;

  private User user;

  public User getUser() {
    return user;
  }

  public void setUser(User user) {
    this.user = user;
  }

  public CurrentUser(User user) {
    super(user.getEmail(),
        user.getPassword(),
        true,
        true,
        true,
        user.getLocked() == null || !user.getLocked(),
        user.getRoles().stream()
            .map(e -> new SimpleGrantedAuthority(e.getName())).collect(Collectors.toList()));

    this.user = user;
  }

}
