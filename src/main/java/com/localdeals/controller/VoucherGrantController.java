package com.localdeals.controller;

import com.localdeals.dto.Result;
import com.localdeals.dto.UserDTO;
import com.localdeals.exception.ApiStatusException;
import com.localdeals.service.VoucherCampaignUserService;
import com.localdeals.utils.UserHolder;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/voucher-grants")
public class VoucherGrantController {
    private final VoucherCampaignUserService userService;

    public VoucherGrantController(VoucherCampaignUserService userService) {
        this.userService = userService;
    }

    @GetMapping("/mine")
    public Result mine() {
        UserDTO user = UserHolder.getUser();
        if (user == null || user.getId() == null) {
            throw new ApiStatusException(HttpStatus.UNAUTHORIZED, "登录已失效");
        }
        return Result.ok(userService.listMine(user.getId()));
    }
}
