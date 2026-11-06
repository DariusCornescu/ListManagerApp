import hashlib
import json
from typing import List, Optional

from fastapi import APIRouter, Depends, HTTPException, Request, Response
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from .. import models, schemas
from ..auth import get_current_admin_user
from ..database import get_db
from ..websocket_manager import manager


router = APIRouter()


def _commit_delete(db: Session, conflict_detail: dict) -> None:
    """Commit a delete, answering 409 if a linked row appeared after the check."""
    try:
        db.commit()
    except IntegrityError:
        db.rollback()
        raise HTTPException(status_code=409, detail=conflict_detail)


def _etag_matches(if_none_match: Optional[str], etag: str) -> bool:
    if not if_none_match:
        return False
    if if_none_match.strip() == "*":
        return True
    candidates = (value.strip().removeprefix("W/") for value in if_none_match.split(","))
    return etag in candidates


def _conditional_json(request: Request, rows: list) -> Response:
    """Answer with the rows, or a bodyless 304 when the client already holds them.

    The ETag hashes the public body, so it can't reveal anything the body doesn't.
    """
    body = json.dumps(rows, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    etag = f'"{hashlib.sha256(body).hexdigest()[:32]}"'
    headers = {"ETag": etag, "Cache-Control": "no-cache"}
    if _etag_matches(request.headers.get("if-none-match"), etag):
        return Response(status_code=304, headers=headers)
    return Response(content=body, media_type="application/json", headers=headers)


@router.get("/api/catalog/distributors", response_model=List[schemas.DistributorDTO])
def get_distributors(request: Request, db: Session = Depends(get_db)):
    """Get all distributors. Supports If-None-Match."""
    distributors = db.query(models.Distributor).order_by(models.Distributor.id).all()
    return _conditional_json(
        request,
        [schemas.DistributorDTO.model_validate(d).model_dump(mode="json") for d in distributors],
    )


@router.get("/api/catalog/distributors/{distributor_id}", response_model=schemas.DistributorDTO)
def get_distributor(distributor_id: int, db: Session = Depends(get_db)):
    """Get distributor by ID"""
    distributor = db.query(models.Distributor).filter(
        models.Distributor.id == distributor_id
    ).first()

    if not distributor:
        raise HTTPException(status_code=404, detail="Distributor not found")

    return distributor


@router.post("/api/catalog/distributors", response_model=schemas.DistributorDTO)
async def create_distributor(
    distributor: schemas.DistributorCreate,
    db: Session = Depends(get_db),
    current_user: models.User = Depends(get_current_admin_user)
):
    """Create new distributor with real-time notification (admin only)"""
    # Check for duplicates
    existing = db.query(models.Distributor).filter(
        models.Distributor.distributor_name == distributor.distributor_name
    ).first()

    if existing:
        raise HTTPException(
            status_code=400,
            detail=f"Distributor '{distributor.distributor_name}' already exists"
        )

    # Create distributor
    db_distributor = models.Distributor(**distributor.model_dump())
    db.add(db_distributor)
    db.commit()
    db.refresh(db_distributor)

    # Notify all connected clients
    await manager.broadcast({
        "type": "distributor_created",
        "data": {
            "distributor_id": db_distributor.id,
            "distributor_name": db_distributor.distributor_name,
            "user_id": 0,
            "username": "anonymous"
        }
    })

    return db_distributor


@router.put("/api/catalog/distributors/{distributor_id}", response_model=schemas.DistributorDTO)
async def update_distributor(
    distributor_id: int,
    distributor_update: schemas.DistributorCreate,
    db: Session = Depends(get_db),
    current_user: models.User = Depends(get_current_admin_user)
):
    """Update distributor with real-time notification (admin only)"""
    distributor = db.query(models.Distributor).filter(
        models.Distributor.id == distributor_id
    ).first()

    if not distributor:
        raise HTTPException(status_code=404, detail="Distributor not found")

    # Update distributor
    distributor.distributor_name = distributor_update.distributor_name
    db.commit()
    db.refresh(distributor)

    # Notify all connected clients
    await manager.broadcast({
        "type": "distributor_updated",
        "data": {
            "distributor_id": distributor.id,
            "distributor_name": distributor.distributor_name,
            "user_id": 0,
            "username": "anonymous"
        }
    })

    return distributor


@router.delete("/api/catalog/distributors/{distributor_id}")
async def delete_distributor(
    distributor_id: int,
    db: Session = Depends(get_db),
    current_user: models.User = Depends(get_current_admin_user)
):
    """Delete distributor with real-time notification (admin only)"""
    distributor = db.query(models.Distributor).filter(
        models.Distributor.id == distributor_id
    ).first()

    if not distributor:
        raise HTTPException(status_code=404, detail="Distributor not found")

    # No cascade exists: products need a distributor, so they must go first.
    product_count = db.query(models.Product).filter(
        models.Product.distributor_id == distributor_id
    ).count()
    if product_count:
        raise HTTPException(
            status_code=409,
            detail={
                "code": "distributor_has_products",
                "message": "Distributor still has products.",
                "product_count": product_count,
            },
        )

    db.delete(distributor)
    _commit_delete(
        db,
        {"code": "distributor_has_products", "message": "Distributor still has products."},
    )

    # Notify all connected clients
    await manager.broadcast({
        "type": "distributor_deleted",
        "data": {
            "distributor_id": distributor_id,
            "user_id": 0,
            "username": "anonymous"
        }
    })

    return {"message": "Distributor deleted successfully", "id": distributor_id}


@router.get("/api/catalog/products", response_model=List[schemas.ProductPublicDTO])
def get_products(request: Request, search: str = None, db: Session = Depends(get_db)):
    """Get all products, optionally filter by search query. Stays a full list for mobile clients.

    Supports If-None-Match, so a phone can check for changes without downloading the list.
    """
    query = db.query(models.Product)
    if search:
        search_filter = f"%{search}%"
        query = query.filter(
            (models.Product.name.ilike(search_filter)) |
            (models.Product.aliases.ilike(search_filter))
        )

    products = query.order_by(models.Product.id).all()
    return _conditional_json(
        request,
        [schemas.ProductPublicDTO.model_validate(p).model_dump(mode="json") for p in products],
    )


@router.get("/api/catalog/products/{product_id}", response_model=schemas.ProductPublicDTO)
def get_product(product_id: int, db: Session = Depends(get_db)):
    """Get product by ID"""
    product = db.query(models.Product).filter(
        models.Product.id == product_id
    ).first()

    if not product:
        raise HTTPException(status_code=404, detail="Product not found")

    return product


@router.post("/api/catalog/products", response_model=schemas.ProductDTO)
async def create_product(
    product: schemas.ProductCreate,
    db: Session = Depends(get_db),
    current_user: models.User = Depends(get_current_admin_user)
):
    """Create new product with real-time notification (admin only)"""
    # Create product
    db_product = models.Product(**product.model_dump())
    db.add(db_product)
    db.commit()
    db.refresh(db_product)

    # Notify all connected clients
    await manager.broadcast({
        "type": "product_created",
        "data": {
            "product_id": db_product.id,
            "product_name": db_product.name,
            "distributor_id": db_product.distributor_id,
            "aliases": db_product.aliases,
            "user_id": 0,
            "username": "anonymous"
        }
    })

    return db_product


@router.put("/api/catalog/products/{product_id}", response_model=schemas.ProductDTO)
async def update_product(
    product_id: int,
    product_update: schemas.ProductCreate,
    db: Session = Depends(get_db),
    current_user: models.User = Depends(get_current_admin_user)
):
    """Update product with real-time notification (admin only)"""
    product = db.query(models.Product).filter(
        models.Product.id == product_id
    ).first()

    if not product:
        raise HTTPException(status_code=404, detail="Product not found")

    # Update product fields
    product.name = product_update.name
    product.distributor_id = product_update.distributor_id
    product.aliases = product_update.aliases
    # Android and Flutter edit products without sending a price. Only an explicit
    # value, including null from /admin, may change it.
    if "price" in product_update.model_fields_set:
        product.price = product_update.price

    db.commit()
    db.refresh(product)

    # Notify all connected clients
    await manager.broadcast({
        "type": "product_updated",
        "data": {
            "product_id": product.id,
            "product_name": product.name,
            "distributor_id": product.distributor_id,
            "aliases": product.aliases,
            "user_id": 0,
            "username": "anonymous"
        }
    })

    return product


@router.delete("/api/catalog/products/{product_id}")
async def delete_product(
    product_id: int,
    db: Session = Depends(get_db),
    current_user: models.User = Depends(get_current_admin_user)
):
    """Delete product with real-time notification (admin only)"""
    product = db.query(models.Product).filter(
        models.Product.id == product_id
    ).first()

    if not product:
        raise HTTPException(status_code=404, detail="Product not found")

    # A list row keeps its product forever, so past orders stay readable.
    list_item_count = db.query(models.GlobalSessionItem).filter(
        models.GlobalSessionItem.product_id == product_id
    ).count()
    if list_item_count:
        raise HTTPException(
            status_code=409,
            detail={
                "code": "product_on_lists",
                "message": "Product is used on lists.",
                "list_item_count": list_item_count,
            },
        )

    db.delete(product)
    _commit_delete(db, {"code": "product_on_lists", "message": "Product is used on lists."})

    # Notify all connected clients
    await manager.broadcast({
        "type": "product_deleted",
        "data": {
            "product_id": product_id,
            "user_id": 0,
            "username": "anonymous"
        }
    })

    return {"message": "Product deleted successfully", "id": product_id}
