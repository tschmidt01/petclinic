package victor.training.petclinic.repository;

import io.zonky.test.db.AutoConfigureEmbeddedDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import victor.training.petclinic.model.Owner;

import jakarta.transaction.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@AutoConfigureEmbeddedDatabase(provider = AutoConfigureEmbeddedDatabase.DatabaseProvider.ZONKY)
@Transactional
class OwnerRepositoryPagingTest {

    @Autowired
    OwnerRepository ownerRepository;

    @BeforeEach
    void seed() {
        for (String last : new String[] {"Zulu", "Alpha", "Mike"}) {
            Owner o = new Owner();
            o.setFirstName("Test");
            o.setLastName(last);
            o.setAddress("addr");
            o.setCity("city");
            o.setTelephone("0000000000");
            ownerRepository.save(o);
        }
    }

    @Test
    void paginates_andReportsTotal() {
        Page<Owner> page = ownerRepository.searchOwners("Test",
            PageRequest.of(0, 2, Sort.by("lastName").ascending()));

        assertThat(page.getContent()).hasSize(2);
        assertThat(page.getTotalElements()).isGreaterThanOrEqualTo(3);
    }

    @Test
    void sortsDescending() {
        Page<Owner> page = ownerRepository.searchOwners("Test",
            PageRequest.of(0, 10, Sort.by("lastName").descending()));

        assertThat(page.getContent().get(0).getLastName())
            .isGreaterThanOrEqualTo(page.getContent().get(1).getLastName());
    }
}
