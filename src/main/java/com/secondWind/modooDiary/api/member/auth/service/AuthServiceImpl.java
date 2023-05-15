package com.secondWind.modooDiary.api.member.auth.service;

import com.secondWind.modooDiary.api.diary.domain.request.MemberLoginDTO;
import com.secondWind.modooDiary.api.diary.domain.request.TokenDTO;
import com.secondWind.modooDiary.api.diary.domain.spec.AdminSpecification;
import com.secondWind.modooDiary.api.member.auth.domain.dto.MemberJoinDTO;
import com.secondWind.modooDiary.api.member.auth.domain.dto.MemberResponseDTO;
import com.secondWind.modooDiary.api.member.auth.domain.dto.PasswordUpdateRequest;
import com.secondWind.modooDiary.api.member.auth.domain.dto.TokenRequestDTO;
import com.secondWind.modooDiary.api.member.auth.domain.spec.PasswordSpecification;
import com.secondWind.modooDiary.api.member.domain.entity.Member;
import com.secondWind.modooDiary.api.member.repository.MemberRepository;
import com.secondWind.modooDiary.common.exception.ApiException;
import com.secondWind.modooDiary.common.exception.CustomAuthException;
import com.secondWind.modooDiary.common.exception.code.AuthErrorCode;
import com.secondWind.modooDiary.common.exception.code.MemberErrorCode;
import com.secondWind.modooDiary.common.provider.JwtTokenProvider;
import com.secondWind.modooDiary.common.result.JsonResultData;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.authentication.builders.AuthenticationManagerBuilder;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService{

    private final PasswordEncoder passwordEncoder;

    private final MemberRepository memberRepository;

    private final PasswordSpecification passwordSpecification;

    private final AuthenticationManagerBuilder authenticationManagerBuilder;

    private final AdminSpecification adminSpecification;

    private final JwtTokenProvider jwtTokenProvider;

    private final StringRedisTemplate redisTemplate;

    @Override
    @Transactional
    public MemberResponseDTO signup(MemberJoinDTO memberJoinDTO) {

        passwordSpecification.check(memberJoinDTO.getPassword());

        if (memberRepository.existsByEmailAndIsDeletedFalse(memberJoinDTO.getEmail())) {
            throw new CustomAuthException(JsonResultData
                    .failResultBuilder()
                    .errorMessage(AuthErrorCode.ALREADY_JOIN_USER.getMessage())
                    .errorCode(AuthErrorCode.ALREADY_JOIN_USER.getCode())
                    .build());
        }

        Member member = memberJoinDTO.toMember(memberJoinDTO, passwordEncoder);
        memberRepository.save(member);

        return MemberResponseDTO.toResponse(member);
    }

    @Override
    @Transactional
    public TokenDTO login(MemberLoginDTO memberLoginDTO) {
        UsernamePasswordAuthenticationToken authenticationToken = memberLoginDTO.toAuthentication();

        Authentication authentication = authenticationManagerBuilder.getObject().authenticate(authenticationToken);

        if (memberLoginDTO.getIsAdmin()) {
            adminSpecification.check(authentication);
        }

        return getTokenDTO(authentication);
    }

    @Override
    public TokenDTO reissue(TokenRequestDTO tokenRequestDTO) {
        if (!jwtTokenProvider.validateToken(tokenRequestDTO.getRefreshToken())) {
            throw new CustomAuthException(JsonResultData
                    .failResultBuilder()
                    .errorMessage(AuthErrorCode.NOT_VALID_TOKEN.getMessage())
                    .errorCode(AuthErrorCode.NOT_VALID_TOKEN.getCode())
                    .build());
        }

        Authentication authentication = jwtTokenProvider.getAuthentication(tokenRequestDTO.getAccessToken());

        String refreshToken = redisTemplate.opsForValue().get("RT:" + authentication.getName());
        if (refreshToken == null || !refreshToken.equals(tokenRequestDTO.getRefreshToken())) {
            throw new CustomAuthException(JsonResultData
                    .failResultBuilder()
                    .errorMessage(AuthErrorCode.NOT_MATCH_TOKEN_INFO.getMessage())
                    .errorCode(AuthErrorCode.NOT_MATCH_TOKEN_INFO.getCode())
                    .build());
        }

        return getTokenDTO(authentication);
    }

    @Override
    public void logout(TokenRequestDTO tokenRequestDTO) {
        if (!jwtTokenProvider.validateToken(tokenRequestDTO.getAccessToken())) {
            throw new CustomAuthException(JsonResultData
                    .failResultBuilder()
                    .errorMessage(AuthErrorCode.NOT_VALID_TOKEN.getMessage())
                    .errorCode(AuthErrorCode.NOT_VALID_TOKEN.getCode())
                    .build());
        }

        Authentication authentication = jwtTokenProvider.getAuthentication(tokenRequestDTO.getAccessToken());

        if (redisTemplate.opsForValue().get("RT:" + authentication.getName()) != null) {
            redisTemplate.delete("RT:" + authentication.getName());
        }

        Long expiration = jwtTokenProvider.getExpiration(tokenRequestDTO.getAccessToken());
        redisTemplate.opsForValue()
                .set(tokenRequestDTO.getAccessToken(), "logout", expiration, TimeUnit.MILLISECONDS);
    }

    @Override
    public void updatePassword(PasswordUpdateRequest passwordUpdateRequest) {
        Member member = memberRepository.findByEmail(passwordUpdateRequest.getEmail())
                .orElseThrow(() -> ApiException.builder()
                        .status(HttpStatus.BAD_REQUEST)
                        .errorCode(MemberErrorCode.NOT_FOUND_MEMBER.getCode())
                        .errorMessage(MemberErrorCode.NOT_FOUND_MEMBER.getMessage())
                        .build());

        member.changePassword(passwordUpdateRequest.getPassword(), passwordEncoder);
    }

    private TokenDTO getTokenDTO(Authentication authentication) {
        TokenDTO tokenDTO = jwtTokenProvider.generateTokenDTO(authentication);

        redisTemplate.opsForValue()
                .set("RT:" + authentication.getName(),
                        tokenDTO.getRefreshToken(),
                        tokenDTO.getAccessTokenExpiresIn(),
                        TimeUnit.MILLISECONDS);

        Optional<Member> optionalMember = memberRepository.findById(Long.valueOf(authentication.getName()));
        if (optionalMember.isPresent()) {
            Member member = optionalMember.get();
            member.changeLastAccessToken(tokenDTO.getAccessToken());
        }

        return tokenDTO;
    }
}
