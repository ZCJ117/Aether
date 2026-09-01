package cn.zcj.aether.config;

import cn.zcj.aether.infrastructure.persistence.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.lang.reflect.Field;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DataInitializerTest {

    private void injectRepository(DataInitializer init, UserRepository repo) throws Exception {
        Field f = DataInitializer.class.getDeclaredField("userRepository");
        f.setAccessible(true);
        f.set(init, repo);
    }

    @Test
    void createsAdminWhenBootstrapEnabledAndPasswordProvided() throws Exception {
        UserRepository repo = mock(UserRepository.class);
        when(repo.findByUsername("admin")).thenReturn(Optional.empty());
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        when(encoder.encode("env-pass-123")).thenReturn("encoded");

        DataInitializer init = new DataInitializer(encoder, true, "env-pass-123");
        injectRepository(init, repo);
        init.run();

        verify(repo).save(argThat(u ->
                "admin".equals(u.getUsername()) && "encoded".equals(u.getPassword())));
    }

    @Test
    void skipsWhenBootstrapDisabled() throws Exception {
        UserRepository repo = mock(UserRepository.class);
        DataInitializer init = new DataInitializer(mock(PasswordEncoder.class), false, "env-pass-123");
        injectRepository(init, repo);
        init.run();
        verifyNoInteractions(repo);
    }

    @Test
    void skipsWhenPasswordMissing() throws Exception {
        UserRepository repo = mock(UserRepository.class);
        DataInitializer init = new DataInitializer(mock(PasswordEncoder.class), true, "");
        injectRepository(init, repo);
        init.run();
        verifyNoInteractions(repo);
    }
}
