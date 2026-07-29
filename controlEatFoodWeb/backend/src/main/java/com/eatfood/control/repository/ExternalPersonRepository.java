package com.eatfood.control.repository;

import com.eatfood.control.domain.ExternalPerson;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ExternalPersonRepository extends JpaRepository<ExternalPerson, Long> {

    Optional<ExternalPerson> findByIdentityCard(String identityCard);

    boolean existsByIdentityCard(String identityCard);

    /** ¿Otra persona externa (distinta de id) ya usa esta cédula? Para validar en la edición. */
    boolean existsByIdentityCardAndIdNot(String identityCard, Long id);

    @Query("""
            SELECT p FROM ExternalPerson p
            WHERE (:term IS NULL OR LOWER(p.fullName) LIKE LOWER(CONCAT('%', :term, '%'))
                   OR p.identityCard LIKE CONCAT('%', :term, '%'))
            ORDER BY p.fullName
            """)
    Page<ExternalPerson> search(@Param("term") String term, Pageable pageable);
}
