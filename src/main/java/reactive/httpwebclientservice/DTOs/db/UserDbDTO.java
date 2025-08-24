package reactive.httpwebclientservice.DTOs.db;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;



public class UserDbDTO {

    Long id;

    @Valid
    @NotNull(message = "Registration data is required")
    private RegistrationDataDbDTO registrationDataDbDTO;

    @Valid
    @NotNull(message = "Profile data is required")
    private ProfileDataDbDTO profileDataDbDTO;

    @Valid
    @NotNull(message = "Description data is required")
    private DescriptionDataDbDTO descriptionDataDbDTO;

    public UserDbDTO() {
    }

    public UserDbDTO(Long id, RegistrationDataDbDTO registrationDataDbDTO, ProfileDataDbDTO profileDataDbDTO, DescriptionDataDbDTO descriptionDataDbDTO) {
        this.id = id;
        this.registrationDataDbDTO = registrationDataDbDTO;
        this.profileDataDbDTO = profileDataDbDTO;
        this.descriptionDataDbDTO = descriptionDataDbDTO;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public RegistrationDataDbDTO getRegistrationDataDbDTO() {
        return registrationDataDbDTO;
    }

    public void setRegistrationDataDbDTO(RegistrationDataDbDTO registrationDataDbDTO) {
        this.registrationDataDbDTO = registrationDataDbDTO;
    }

    public ProfileDataDbDTO getProfileDataDbDTO() {
        return profileDataDbDTO;
    }

    public void setProfileDataDbDTO(ProfileDataDbDTO profileDataDbDTO) {
        this.profileDataDbDTO = profileDataDbDTO;
    }

    public DescriptionDataDbDTO getDescriptionDataDbDTO() {
        return descriptionDataDbDTO;
    }

    public void setDescriptionDataDbDTO(DescriptionDataDbDTO descriptionDataDbDTO) {
        this.descriptionDataDbDTO = descriptionDataDbDTO;
    }

    @Override
    public String toString() {
        return "UserDbDTO{" + '\n' +
                "registrationDataDbDTO=" + registrationDataDbDTO +
                ", profileDataDbDTO=" + profileDataDbDTO +
                ", descriptionDataDbDTO=" + descriptionDataDbDTO +
                '}';
    }
}
