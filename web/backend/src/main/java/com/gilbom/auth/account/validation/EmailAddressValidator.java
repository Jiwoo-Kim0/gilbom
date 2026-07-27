package com.gilbom.auth.account.validation;

import com.gilbom.auth.account.EmailAddressPolicy;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class EmailAddressValidator
        implements ConstraintValidator<ValidEmailAddress, String> {

    @Override
    public boolean isValid(
            String value,
            ConstraintValidatorContext context
    ) {
        return EmailAddressPolicy.hasValidFormat(value);
    }
}
