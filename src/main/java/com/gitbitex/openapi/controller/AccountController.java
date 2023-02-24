package com.gitbitex.openapi.controller;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.gitbitex.marketdata.entity.Account;
import com.gitbitex.marketdata.repository.AccountRepository;
import com.gitbitex.openapi.model.AccountDto;
import com.gitbitex.order.ClientOrderReceiver;
import com.gitbitex.user.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class AccountController {
    private final AccountRepository accountRepository;
    private final ClientOrderReceiver clientOrderReceiver;

    @GetMapping("/accounts")
    public List<AccountDto> getAccounts(@RequestParam(name = "currency") List<String> currencies,
        @RequestAttribute(required = false) User currentUser) {
        if (currentUser == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }

        List<AccountDto> accounts = new ArrayList<>();
        for (String currency : currencies) {
            Account account = accountRepository.findAccountByUserIdAndCurrency(currentUser.getUserId(), currency);
            if (account != null) {
                accounts.add(accountDto(account));
            } else {
                AccountDto accountDto = new AccountDto();
                accountDto.setCurrency(currency);
                accountDto.setAvailable("0");
                accountDto.setHold("0");
                accounts.add(accountDto);
            }
        }
        return accounts;
    }

    @GetMapping("/admin/deposit")
    public void deposit(@RequestParam String userId, @RequestParam String currency, @RequestParam String amount) {
        clientOrderReceiver.deposit(userId, currency, new BigDecimal(amount), UUID.randomUUID().toString());
    }

    private AccountDto accountDto(Account account) {
        AccountDto accountDto = new AccountDto();
        accountDto.setId(account.getUserId());
        accountDto.setCurrency(account.getCurrency());
        accountDto.setAvailable(account.getAvailable() != null ? account.getAvailable().toPlainString() : "0");
        accountDto.setHold(account.getHold() != null ? account.getHold().toPlainString() : "0");
        return accountDto;
    }

}
