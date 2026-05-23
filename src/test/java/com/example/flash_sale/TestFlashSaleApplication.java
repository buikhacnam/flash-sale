package com.example.flash_sale;

import org.springframework.boot.SpringApplication;

public class TestFlashSaleApplication {

	public static void main(String[] args) {
		SpringApplication.from(FlashSaleApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
