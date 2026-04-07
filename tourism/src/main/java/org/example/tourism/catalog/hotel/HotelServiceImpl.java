package org.example.tourism.catalog.hotel;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.tourism.booking.Booking;
import org.example.tourism.booking.BookingRepository;
import org.example.tourism.booking.BookingStatus;
import org.example.tourism.catalog.hotel.dto.*;
import org.example.tourism.catalog.roomtype.RoomType;
import org.example.tourism.catalog.roomtype.dto.RoomTypeAvailabilityDto;
import org.example.tourism.catalog.roomtype.RoomTypeRepository;
import org.example.tourism.catalog.roomtype.dto.RoomTypeSummaryDto;
import org.example.tourism.catalog.shared.CityInfoDto;
import org.example.tourism.catalog.shared.PriceBreakdownDto;
import org.example.tourism.catalog.shared.ReviewRequestDto;
import org.example.tourism.catalog.shared.ReviewResponseDto;
import org.example.tourism.common.DateTooFarException;
import org.example.tourism.common.HotelHasActiveBookingsException;
import org.example.tourism.common.ReviewNotAllowedException;
import org.example.tourism.security.User;
import org.example.tourism.security.UserRepository;
import org.example.tourism.wishlist.WishlistService;
import org.springframework.beans.BeanUtils;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.criteria.Predicate;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class HotelServiceImpl implements HotelService {

    private final HotelRepository hotelRepository;
    private final RoomTypeRepository roomTypeRepository;
    private final BookingRepository bookingRepository;
    private final HotelReviewRepository reviewRepository;
    private final WishlistService wishlistService;
    private final UserRepository userRepository;

    @Override
    @Transactional
    public HotelResponseDto createHotel(HotelRequestDto request) {
        log.info("Creating new hotel: {}", request.getName());

        Hotel hotel = new Hotel();
        BeanUtils.copyProperties(request, hotel);

        if (request.getAmenities() != null && !request.getAmenities().isEmpty()) {
            hotel.setAmenities(new HashSet<>(request.getAmenities()));
        }

        if (request.getImageUrls() != null && !request.getImageUrls().isEmpty()) {
            hotel.setImageUrls(new ArrayList<>(request.getImageUrls()));
        }

        if (request.getSpokenLanguages() != null && !request.getSpokenLanguages().isEmpty()) {
            hotel.setSpokenLanguages(new HashSet<>(request.getSpokenLanguages()));
        }


        if (hotel.getCheckInTime() == null) hotel.setCheckInTime("15:00");
        if (hotel.getCheckOutTime() == null) hotel.setCheckOutTime("11:00");
        if (hotel.getTotalRooms() == null) hotel.setTotalRooms(100);
        if (hotel.getChildrenAllowed() == null) hotel.setChildrenAllowed(true);
        if (hotel.getPetsAllowed() == null) hotel.setPetsAllowed(false);
        if (hotel.getSmokingAllowed() == null) hotel.setSmokingAllowed(false);
        if (hotel.getAmenities() == null) hotel.setAmenities(new HashSet<>());
        if (hotel.getImageUrls() == null) hotel.setImageUrls(new ArrayList<>());
        if (hotel.getSpokenLanguages() == null) hotel.setSpokenLanguages(new HashSet<>());
        if (hotel.getNearbyAttractions() == null) hotel.setNearbyAttractions("[]");
        if (hotel.getFeatured() == null) hotel.setFeatured(false);
        if (hotel.getFeaturedOrder() == null) hotel.setFeaturedOrder(0);
        if (hotel.getAverageRating() == null) hotel.setAverageRating(0.0);
        if (hotel.getReviewCount() == null) hotel.setReviewCount(0);
        if (hotel.getWishlistCount() == null) hotel.setWishlistCount(0);
        if (hotel.getBookingCount() == null) hotel.setBookingCount(0);

        Hotel savedHotel = hotelRepository.save(hotel);
        log.info("Hotel created with ID: {}", savedHotel.getId());

        return mapToResponseDto(savedHotel);
    }

    @Override
    @Transactional(readOnly = true)
    public HotelDetailResponseDto getHotelDetail(Long id) {

        Hotel hotel = hotelRepository.findByIdWithRoomTypes(id)
                .orElseThrow(() -> new EntityNotFoundException("Hotel not found"));

        return mapToDetailDto(hotel);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<HotelSearchResponseDto> searchHotels(HotelSearchCriteria criteria, Pageable pageable) {
        log.info("Searching hotels with enhanced criteria: {}", criteria);


        Specification<Hotel> spec = HotelSpecificationBuilder.buildSpecification(criteria);


        if (StringUtils.hasText(criteria.getSortBy())) {
            pageable = applySorting(criteria.getSortBy(), pageable);
        }

        Page<Hotel> hotels = hotelRepository.findAll(spec, pageable);

        List<HotelSearchResponseDto> dtos = hotels.getContent().stream()
                .map(this::mapToSearchDto)
                .collect(Collectors.toList());

        return new PageImpl<>(dtos, pageable, hotels.getTotalElements());
    }

    private Pageable applySorting(String sortBy, Pageable pageable) {
        Sort sort = switch (sortBy) {
            case "price_asc" -> Sort.by("roomTypes.basePrice").ascending();
            case "price_desc" -> Sort.by("roomTypes.basePrice").descending();
            case "rating_desc" -> Sort.by("averageRating").descending();
            case "popularity" -> Sort.by("bookingCount").descending();
            case "newest" -> Sort.by("createdAt").descending();
            default -> Sort.unsorted();
        };

        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), sort);
    }

    @Override
    @Transactional(readOnly = true)
    public List<HotelSearchResponseDto> getFeaturedHotels() {
        return hotelRepository.findByFeaturedTrue().stream()
                .map(this::mapToSearchDto)
                .limit(6)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<CityInfoDto> getAvailableCities() {
        List<Object[]> results = hotelRepository.countHotelsByCity();
        return results.stream()
                .map(row -> CityInfoDto.builder()
                        .city((String) row[0])
                        .country((String) row[1])
                        .hotelCount(((Number) row[2]).intValue())
                        .build())
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public HotelAvailabilityDto checkHotelAvailability(Long hotelId, LocalDate checkIn, LocalDate checkOut, Integer guests) {
        log.info("Checking availability for hotel {} from {} to {} for {} guests",
                hotelId, checkIn, checkOut, guests);


        if (checkIn.isAfter(checkOut) || checkIn.isEqual(checkOut)) {
            throw new IllegalArgumentException("Check-out date must be after check-in date");
        }

        if (checkIn.isBefore(LocalDate.now())) {
            throw new IllegalArgumentException("Check-in date cannot be in the past");
        }

        if (checkIn.isAfter(LocalDate.now().plusYears(1))) {
            throw new DateTooFarException("Bookings can only be made up to 1 year in advance");
        }

        Hotel hotel = hotelRepository.findById(hotelId)
                .orElseThrow(() -> new EntityNotFoundException("Hotel not found"));

        List<RoomType> roomTypes = roomTypeRepository.findByHotelIdAndCapacityGreaterThanEqual(hotelId, guests);

        List<RoomTypeAvailabilityDto> availableRooms = new ArrayList<>();
        for (RoomType roomType : roomTypes) {
            List<Booking> overlapping = bookingRepository.findOverlappingBookings(
                    roomType.getId(), checkIn, checkOut);

            if (overlapping.isEmpty()) {
                long nights = ChronoUnit.DAYS.between(checkIn, checkOut);
                BigDecimal totalPrice = roomType.getBasePrice().multiply(BigDecimal.valueOf(nights));

                availableRooms.add(RoomTypeAvailabilityDto.builder()
                        .id(roomType.getId())
                        .name(roomType.getName())
                        .capacity(roomType.getCapacity())
                        .pricePerNight(roomType.getBasePrice())
                        .totalPrice(totalPrice)
                        .bedType(roomType.getBedType())
                        .amenities(String.join(", ", roomType.getAmenities()))
                        .refundable(true)
                        .build());
            }
        }

        long nights = ChronoUnit.DAYS.between(checkIn, checkOut);
        BigDecimal subtotal = availableRooms.isEmpty() ? BigDecimal.ZERO :
                availableRooms.get(0).getTotalPrice();
        BigDecimal taxes = subtotal.multiply(new BigDecimal("0.12"));

        return HotelAvailabilityDto.builder()
                .hotelId(hotelId)
                .hotelName(hotel.getName())
                .hotelAddress(hotel.getAddress())
                .hotelStarRating(hotel.getStarRating())
                .hotelImage(hotel.getImageUrls().isEmpty() ? null : hotel.getImageUrls().get(0))
                .checkIn(checkIn)
                .checkOut(checkOut)
                .nights((int) nights)
                .guests(guests)
                .available(!availableRooms.isEmpty())
                .availableRooms(availableRooms)
                .priceBreakdown(PriceBreakdownDto.builder()
                        .subtotal(subtotal)
                        .taxes(taxes)
                        .total(subtotal.add(taxes))
                        .currency("USD")
                        .build())
                .build();
    }
    @Override
    @Transactional(readOnly = true)
    public List<HotelAvailabilityDto> searchAvailableRooms(LocalDate checkIn, LocalDate checkOut, Integer guests,
                                                           String city, Integer starRating, BigDecimal maxPrice) {
        log.info("Searching available rooms across all hotels: dates {} to {}, guests: {}", checkIn, checkOut, guests);


        Specification<Hotel> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (StringUtils.hasText(city)) {
                predicates.add(cb.like(cb.lower(root.get("city")), "%" + city.toLowerCase() + "%"));
            }

            if (starRating != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("starRating"), starRating));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };


        List<Hotel> hotels = hotelRepository.findAll(spec);
        List<HotelAvailabilityDto> results = new ArrayList<>();


        for (Hotel hotel : hotels) {

            List<RoomType> roomTypes = roomTypeRepository.findByHotelIdAndCapacityGreaterThanEqual(hotel.getId(), guests);


            if (maxPrice != null) {
                roomTypes = roomTypes.stream()
                        .filter(rt -> rt.getBasePrice().compareTo(maxPrice) <= 0)
                        .collect(Collectors.toList());
            }

            List<RoomTypeAvailabilityDto> availableRooms = new ArrayList<>();


            for (RoomType roomType : roomTypes) {
                List<Booking> overlapping = bookingRepository.findOverlappingBookings(
                        roomType.getId(), checkIn, checkOut);

                if (overlapping.isEmpty()) {
                    long nights = ChronoUnit.DAYS.between(checkIn, checkOut);
                    BigDecimal totalPrice = roomType.getBasePrice().multiply(BigDecimal.valueOf(nights));

                    availableRooms.add(RoomTypeAvailabilityDto.builder()
                            .id(roomType.getId())
                            .name(roomType.getName())
                            .capacity(roomType.getCapacity())
                            .pricePerNight(roomType.getBasePrice())
                            .totalPrice(totalPrice)
                            .bedType(roomType.getBedType())
                            .amenities(String.join(", ", roomType.getAmenities()))
                            .refundable(true)
                            .build());
                }
            }


            if (!availableRooms.isEmpty()) {
                long nights = ChronoUnit.DAYS.between(checkIn, checkOut);
                BigDecimal subtotal = availableRooms.get(0).getTotalPrice();
                BigDecimal taxes = subtotal.multiply(new BigDecimal("0.12"));

                results.add(HotelAvailabilityDto.builder()
                        .hotelId(hotel.getId())
                        .hotelName(hotel.getName())
                        .hotelAddress(hotel.getAddress())
                        .hotelStarRating(hotel.getStarRating())
                        .hotelImage(hotel.getImageUrls().isEmpty() ? null : hotel.getImageUrls().get(0))
                        .checkIn(checkIn)
                        .checkOut(checkOut)
                        .nights((int) nights)
                        .guests(guests)
                        .available(true)
                        .availableRooms(availableRooms)
                        .priceBreakdown(PriceBreakdownDto.builder()
                                .subtotal(subtotal)
                                .taxes(taxes)
                                .total(subtotal.add(taxes))
                                .currency("USD")
                                .build())
                        .build());
            }
        }

        results.sort(Comparator.comparing(r -> r.getPriceBreakdown().getTotal()));

        log.info("Found {} hotels with available rooms", results.size());
        return results;
    }

    @Override
    @Transactional
    public HotelResponseDto addHotelImages(Long hotelId, List<String> imageUrls) {
        Hotel hotel = hotelRepository.findById(hotelId)
                .orElseThrow(() -> new EntityNotFoundException("Hotel not found"));

        hotel.getImageUrls().addAll(imageUrls);
        Hotel updatedHotel = hotelRepository.save(hotel);

        return mapToResponseDto(updatedHotel);
    }

    @Override
    @Transactional
    public HotelResponseDto updateHotel(Long id, HotelRequestDto request) {
        Hotel hotel = hotelRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Hotel not found with id: " + id));

        BeanUtils.copyProperties(request, hotel, "id", "createdAt", "roomTypes", "reviews");
        Hotel updatedHotel = hotelRepository.save(hotel);

        return mapToResponseDto(updatedHotel);
    }

    @Override
    @Transactional
    public void deleteHotel(Long id) {
        log.info("Deleting hotel with ID: {}", id);

        Hotel hotel = hotelRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Hotel not found with id: " + id));

        // Check for active bookings
        long activeBookings = bookingRepository.countByHotelIdAndStatus(id, BookingStatus.CONFIRMED);

        if (activeBookings > 0) {
            throw new HotelHasActiveBookingsException(
                    String.format("Cannot delete hotel with %d active bookings. Please cancel all future bookings first.", activeBookings)
            );
        }

        // Also check for pending bookings
        long pendingBookings = bookingRepository.countByHotelIdAndStatus(id, BookingStatus.PENDING);
        if (pendingBookings > 0) {
            throw new HotelHasActiveBookingsException(
                    String.format("Cannot delete hotel with %d pending bookings.", pendingBookings)
            );
        }

        hotelRepository.deleteById(id);
        log.info("Hotel {} deleted successfully", id);
    }

    private HotelResponseDto mapToResponseDto(Hotel hotel) {
        HotelResponseDto dto = new HotelResponseDto();
        BeanUtils.copyProperties(hotel, dto);
        return dto;
    }

    private HotelDetailResponseDto mapToDetailDto(Hotel hotel) {
        List<RoomTypeSummaryDto> roomTypeSummaries = hotel.getRoomTypes().stream()
                .map(rt -> RoomTypeSummaryDto.builder()
                        .id(rt.getId())
                        .name(rt.getName())
                        .capacity(rt.getCapacity())
                        .basePrice(rt.getBasePrice())
                        .bedType(rt.getBedType())
                        .amenities(rt.getAmenities())
                        .imageUrl(rt.getImageUrls().isEmpty() ? null : rt.getImageUrls().get(0))
                        .available(true)
                        .build())
                .collect(Collectors.toList());

        Long wishlistCount = wishlistService.getWishlistCount(hotel.getId());

        return HotelDetailResponseDto.builder()
                // Basic info
                .id(hotel.getId())
                .name(hotel.getName())
                .description(hotel.getDescription())
                .city(hotel.getCity())
                .country(hotel.getCountry())
                .address(hotel.getAddress())
                .zipCode(hotel.getZipCode())
                .latitude(hotel.getLatitude())
                .longitude(hotel.getLongitude())
                .starRating(hotel.getStarRating())
                .contactEmail(hotel.getContactEmail())
                .contactPhone(hotel.getContactPhone())
                .website(hotel.getWebsite())
                .checkInTime(hotel.getCheckInTime())
                .checkOutTime(hotel.getCheckOutTime())
                .totalRooms(hotel.getTotalRooms())

                // Policies
                .childrenAllowed(hotel.getChildrenAllowed())
                .petsAllowed(hotel.getPetsAllowed())
                .petPolicy(hotel.getPetPolicy())
                .smokingAllowed(hotel.getSmokingAllowed())
                .smokingPolicy(hotel.getSmokingPolicy())
                .cancellationPolicy(hotel.getCancellationPolicy())
                .paymentPolicy(hotel.getPaymentPolicy())
                .checkInInstructions(hotel.getCheckInInstructions())

                // Features
                .amenities(new ArrayList<>(hotel.getAmenities()))
                .imageUrls(hotel.getImageUrls())
                .spokenLanguages(hotel.getSpokenLanguages())
                .nearbyAttractions(hotel.getNearbyAttractions())

                // Room Types
                .roomTypes(roomTypeSummaries)

                // Stats
                .averageRating(hotel.getAverageRating())
                .reviewCount(hotel.getReviewCount())
                .featured(hotel.getFeatured())
                .featuredOrder(hotel.getFeaturedOrder())
                .dealTag(hotel.getDealTag())
                .wishlistCount(wishlistCount)
                .build();
    }

    private HotelSearchResponseDto mapToSearchDto(Hotel hotel) {
        BigDecimal minPrice = hotel.getRoomTypes().stream()
                .map(RoomType::getBasePrice)
                .min(BigDecimal::compareTo)
                .orElse(BigDecimal.ZERO);

        return HotelSearchResponseDto.builder()
                .id(hotel.getId())
                .name(hotel.getName())
                .city(hotel.getCity())
                .country(hotel.getCountry())
                .starRating(hotel.getStarRating())
                .mainImageUrl(hotel.getImageUrls().isEmpty() ? null : hotel.getImageUrls().get(0))
                .averageRating(hotel.getAverageRating() != null ? hotel.getAverageRating() : 0.0)
                .reviewCount(hotel.getReviewCount() != null ? hotel.getReviewCount() : 0)
                .priceFrom(minPrice)
                .available(true)
                .build();
    }

    @Override
    @Transactional
    public ReviewResponseDto addHotelReview(Long hotelId, Long userId, ReviewRequestDto request) {
        log.info("Adding review for hotel ID: {} by user ID: {}", hotelId, userId);

        Hotel hotel = hotelRepository.findById(hotelId)
                .orElseThrow(() -> new EntityNotFoundException("Hotel not found with id: " + hotelId));

        if (reviewRepository.existsByHotelIdAndUserId(hotelId, userId)) {
            throw new IllegalStateException("User has already reviewed this hotel");
        }

        boolean hasVerifiedBooking = bookingRepository.existsByHotelIdAndUserIdAndStatus(
                hotelId, userId, BookingStatus.CONFIRMED);
        if (!hasVerifiedBooking) {
            throw new ReviewNotAllowedException("You can only review hotels you have actually stayed at");
        }

        HotelReview review = new HotelReview();
        review.setHotel(hotel);
        review.setUserId(userId);
        review.setRating(request.getRating());
        review.setTitle(request.getTitle());
        review.setComment(request.getComment());
        review.setStayDate(request.getStayDate());
        review.setVerifiedBooking(checkIfUserHasVerifiedBooking(hotelId, userId));

        HotelReview savedReview = reviewRepository.save(review);

        updateHotelAverageRating(hotel);

        log.info("Review added with ID: {}", savedReview.getId());

        return mapToReviewDto(savedReview);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ReviewResponseDto> getHotelReviews(Long hotelId, Pageable pageable) {
        log.info("Fetching reviews for hotel ID: {}", hotelId);

        return reviewRepository.findByHotelId(hotelId, pageable)
                .map(this::mapToReviewDto);
    }

    @Override
    @Transactional
    public void markReviewHelpful(Long reviewId) {
        HotelReview review = reviewRepository.findById(reviewId)
                .orElseThrow(() -> new EntityNotFoundException("Review not found with id: " + reviewId));

        review.setHelpfulCount(review.getHelpfulCount() + 1);
        reviewRepository.save(review);

        log.info("Review {} marked as helpful. New count: {}", reviewId, review.getHelpfulCount());
    }

    private boolean checkIfUserHasVerifiedBooking(Long hotelId, Long userId) {
        return bookingRepository.existsByHotelIdAndUserIdAndStatus(
                hotelId, userId, BookingStatus.CONFIRMED);
    }

    private void updateHotelAverageRating(Hotel hotel) {
        Double avgRating = reviewRepository.getAverageRatingForHotel(hotel.getId());
        Integer reviewCount = reviewRepository.getReviewCountForHotel(hotel.getId());

        hotel.setAverageRating(avgRating != null ? avgRating : 0.0);
        hotel.setReviewCount(reviewCount != null ? reviewCount : 0);

        hotelRepository.save(hotel);
    }

    private ReviewResponseDto mapToReviewDto(HotelReview review) {
        return ReviewResponseDto.builder()
                .id(review.getId())
                .hotelId(review.getHotel().getId())
                .hotelName(review.getHotel().getName())
                .userId(review.getUserId())
                .userName(getUserName(review.getUserId()))
                .rating(review.getRating())
                .title(review.getTitle())
                .comment(review.getComment())
                .stayDate(review.getStayDate())
                .createdAt(review.getCreatedAt())
                .updatedAt(review.getUpdatedAt())
                .helpfulCount(review.getHelpfulCount())
                .verifiedBooking(review.getVerifiedBooking())
                .build();
    }

    private String getUserName(Long userId) {
        return userRepository.findById(userId)
                .map(User::getUsername)
                .orElse("User " + userId);
    }

    @Override
    @Transactional
    public HotelResponseDto removeHotelImage(Long hotelId, String imageUrl) {
        log.info("Removing image from hotel ID: {}, image URL: {}", hotelId, imageUrl);

        Hotel hotel = hotelRepository.findById(hotelId)
                .orElseThrow(() -> new EntityNotFoundException("Hotel not found with id: " + hotelId));

        boolean removed = hotel.getImageUrls().remove(imageUrl);

        if (!removed) {
            log.warn("Image URL not found in hotel: {}", imageUrl);
            throw new IllegalArgumentException("Image URL not found for this hotel");
        }

        Hotel updatedHotel = hotelRepository.save(hotel);
        log.info("Image removed successfully. Remaining images: {}", updatedHotel.getImageUrls().size());

        return mapToResponseDto(updatedHotel);
    }
}