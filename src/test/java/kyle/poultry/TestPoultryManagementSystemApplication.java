package kyle.poultry;

import org.springframework.boot.SpringApplication;

public class TestPoultryManagementSystemApplication {

    public static void main(String[] args) {
        SpringApplication.from(PoultryManagementSystemApplication::main).with(TestcontainersConfiguration.class).run(args);
    }

}
