package io.github.iakovos3.springtestcontainersminimal;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

class CustomerRepositoryTest extends BaseIntegrationTest {

    @Autowired
    CustomerRepository repository;

    @Test
    void should_persist_customer_when_saved() {
        Customer ada = new Customer();
        ada.setName("Ada Lovelace");
        repository.save(ada);

        assertThat(repository.findAll())
                .extracting(Customer::getName)
                .contains("Ada Lovelace");
    }
}