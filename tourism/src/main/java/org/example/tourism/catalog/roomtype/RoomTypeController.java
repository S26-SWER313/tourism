package org.example.tourism.catalog.roomtype;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.tourism.catalog.roomtype.dto.RoomTypeRequestDto;
import org.example.tourism.catalog.roomtype.dto.RoomTypeResponseDto;
import org.example.tourism.common.FileUploadService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/room-types")
@RequiredArgsConstructor
@Tag(name = "Room Types", description = "Room type management endpoints")
public class RoomTypeController {

    private final RoomTypeService roomTypeService;
    private final FileUploadService fileUploadService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN', 'HOTEL_MANAGER')")
    @Operation(summary = "Create a new room type", description = "Admin or Hotel Manager only")
    public RoomTypeResponseDto createRoomType(@Valid @RequestBody RoomTypeRequestDto request) {
        return roomTypeService.createRoomType(request);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get room type by ID", description = "Any authenticated user")
    public RoomTypeResponseDto getRoomType(@PathVariable Long id) {
        return roomTypeService.getRoomType(id);
    }

    @GetMapping
    @Operation(summary = "Get all room types", description = "Any authenticated user with optional hotel filter")
    public List<RoomTypeResponseDto> getAllRoomTypes(
            @RequestParam(required = false) Long hotelId) {
        if (hotelId != null) {
            return roomTypeService.getRoomTypesByHotel(hotelId);
        }
        return roomTypeService.getAllRoomTypes();
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'HOTEL_MANAGER')")
    @Operation(summary = "Update a room type", description = "Admin or Hotel Manager only")
    public RoomTypeResponseDto updateRoomType(
            @PathVariable Long id,
            @Valid @RequestBody RoomTypeRequestDto request) {
        return roomTypeService.updateRoomType(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Delete a room type", description = "Admin only")
    public void deleteRoomType(@PathVariable Long id) {
        roomTypeService.deleteRoomType(id);
    }

    @PostMapping("/{id}/upload-images")
    @PreAuthorize("hasAnyRole('ADMIN', 'HOTEL_MANAGER')")
    @Operation(summary = "Upload room type images", description = "Upload actual image files for a room type")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "201", description = "Images uploaded successfully"),
            @ApiResponse(responseCode = "400", description = "Invalid file format"),
            @ApiResponse(responseCode = "401", description = "Unauthorized"),
            @ApiResponse(responseCode = "403", description = "Forbidden"),
            @ApiResponse(responseCode = "404", description = "Room type not found")
    })
    public ResponseEntity<List<String>> uploadRoomTypeImages(
            @Parameter(description = "Room type ID", required = true)
            @PathVariable Long id,
            @Parameter(description = "Image files to upload")
            @RequestParam("images") List<MultipartFile> images) {

        log.info("Uploading {} images for room type ID: {}", images.size(), id);

        // Verify room type exists
        roomTypeService.getRoomType(id);

        // Upload images to room-types folder
        List<String> imageUrls = fileUploadService.uploadRoomTypeImages(images);

        if (imageUrls.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }

        // Add URLs to room type
        roomTypeService.addRoomTypeImages(id, imageUrls);

        log.info("Successfully uploaded {} images for room type ID: {}", imageUrls.size(), id);
        return ResponseEntity.status(HttpStatus.CREATED).body(imageUrls);
    }


    @PostMapping("/{id}/upload-image")
    @PreAuthorize("hasAnyRole('ADMIN', 'HOTEL_MANAGER')")
    @Operation(summary = "Upload a single room type image", description = "Upload one image file for a room type")
    public ResponseEntity<String> uploadRoomTypeImage(
            @PathVariable Long id,
            @RequestParam("image") MultipartFile image) {

        List<String> urls = fileUploadService.uploadRoomTypeImages(List.of(image));

        if (urls.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }

        roomTypeService.addRoomTypeImages(id, urls);
        return ResponseEntity.status(HttpStatus.CREATED).body(urls.get(0));
    }


    @DeleteMapping("/{id}/images")
    @PreAuthorize("hasAnyRole('ADMIN', 'HOTEL_MANAGER')")
    @Operation(summary = "Delete room type image", description = "Remove an image from a room type")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "204", description = "Image deleted successfully"),
            @ApiResponse(responseCode = "400", description = "Invalid image URL"),
            @ApiResponse(responseCode = "404", description = "Room type or image not found")
    })
    public ResponseEntity<Void> deleteRoomTypeImage(
            @PathVariable Long id,
            @RequestParam String imageUrl) {

        log.info("Deleting image: {} from room type ID: {}", imageUrl, id);

        // Step 1: Delete the physical file from disk
        boolean fileDeleted = fileUploadService.deleteFile(imageUrl);

        if (!fileDeleted) {
            log.warn("File not found on disk: {}", imageUrl);
            // Continue anyway - maybe the file was already deleted
        }

        // Step 2: Remove the URL from the room type's image list in database
        try {
            roomTypeService.removeRoomTypeImage(id, imageUrl);
        } catch (IllegalArgumentException e) {
            log.error("Failed to remove image from room type: {}", e.getMessage());
            return ResponseEntity.badRequest().build();
        } catch (jakarta.persistence.EntityNotFoundException e) {
            return ResponseEntity.notFound().build();
        }

        log.info("Image deleted successfully from room type: {}", imageUrl);
        return ResponseEntity.noContent().build();
    }


    @DeleteMapping("/{id}/images/{imageName}")
    @PreAuthorize("hasAnyRole('ADMIN', 'HOTEL_MANAGER')")
    @Operation(summary = "Delete room type image by name", description = "Remove an image from a room type using just the filename")
    public ResponseEntity<Void> deleteRoomTypeImageByName(
            @PathVariable Long id,
            @PathVariable String imageName) {

        String imageUrl = "/uploads/room-types/" + imageName;
        log.info("Deleting image: {} from room type ID: {}", imageUrl, id);

        // Delete the physical file
        boolean fileDeleted = fileUploadService.deleteFile(imageUrl);

        if (!fileDeleted) {
            log.warn("File not found on disk: {}", imageUrl);
        }

        // Remove URL from room type's image list
        try {
            roomTypeService.removeRoomTypeImage(id, imageUrl);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        } catch (jakarta.persistence.EntityNotFoundException e) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.noContent().build();
    }
}