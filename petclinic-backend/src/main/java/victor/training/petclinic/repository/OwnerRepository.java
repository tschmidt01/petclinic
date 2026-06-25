package victor.training.petclinic.repository;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import victor.training.petclinic.model.Owner;

public interface OwnerRepository extends Repository<Owner, Integer> {

    @Query("""
        SELECT o FROM Owner o WHERE
          LOWER(o.firstName) LIKE LOWER(CONCAT('%', :search, '%')) OR
          LOWER(o.lastName)  LIKE LOWER(CONCAT('%', :search, '%')) OR
          LOWER(o.address)   LIKE LOWER(CONCAT('%', :search, '%')) OR
          LOWER(o.city)      LIKE LOWER(CONCAT('%', :search, '%')) OR
          LOWER(o.telephone) LIKE LOWER(CONCAT('%', :search, '%')) OR
          EXISTS (SELECT 1 FROM Pet p WHERE p.owner = o
                  AND LOWER(p.name) LIKE LOWER(CONCAT('%', :search, '%')))
        """)
    Page<Owner> searchOwners(@Param("search") String search, Pageable pageable);

    Optional<Owner> findById(int id);

    @Query("SELECT o FROM Owner o LEFT JOIN FETCH o.pets WHERE o.id = :id")
    Optional<Owner> findByIdFetchingPets(int id);

    Owner save(Owner owner);

    void delete(Owner owner);

    long count();

}
